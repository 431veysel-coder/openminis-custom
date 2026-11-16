package com.openminis.app.data.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [MINIS-OZEL 11.1] AES-GCM for vault entries, keyed by the Android Keystore.
 *
 * ## What this protects against, honestly
 *
 * The key is non-exportable and lives in the Keystore, so the ciphertext in
 * `notes.db` is unreadable without this app on this device: a copy of the
 * database pulled off a backup, an `adb backup`, a rooted file copy, or a
 * backup restored onto another phone. It does NOT protect against an attacker
 * who can run code as this app — they can call the same cipher this file calls.
 * That is the same bar as every other credential the app holds, and it is the
 * honest bar; claiming more would be marketing.
 *
 * ## Format
 *
 * `v1:` + base64(iv || ciphertext || tag). Versioned because a future key
 * rotation must be able to tell old rows from new ones, and because a row that
 * does not start with `v1:` is a bug, not a secret.
 *
 * ## Key loss
 *
 * Keystore keys do not survive a device transfer or an app reinstall. When the
 * key is gone, [open] returns null and the UI says the entry cannot be
 * decrypted — it never returns an empty string, because a blank secret that
 * looks valid is worse than a visible failure (the user would paste it
 * somewhere and wonder why auth fails).
 */
internal object VaultCrypto {

    private const val TAG = "VaultCrypto"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "minis_ozel_vault_v1"
    private const val PREFIX = "v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun key(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return@runCatching it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                // No user-authentication requirement on purpose: the vault is
                // read by an agent while the user is in another app. Gating it
                // behind a biometric prompt would mean either a prompt per
                // read or a session-scoped auth that quietly expires mid-task.
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        gen.generateKey()
    }.onFailure { Log.e(TAG, "keystore key unavailable: ${it.message}", it) }.getOrNull()

    /** Encrypts [plain]. Returns "" for an empty input; throws if no key. */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val secretKey = key() ?: error("keystore key unavailable — cannot store a secret")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + sealed
        return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    /**
     * Decrypts a value written by [seal]. Null means "cannot read" — a missing
     * key, a truncated row, an old format. Never throws: a vault row that
     * cannot be opened must not take the notes screen down with it.
     */
    fun open(sealed: String): String? {
        if (sealed.isEmpty()) return ""
        if (!sealed.startsWith(PREFIX)) {
            Log.w(TAG, "unreadable vault row: missing ${PREFIX}prefix")
            return null
        }
        return runCatching {
            val secretKey = key() ?: return null
            val packed = Base64.decode(sealed.removePrefix(PREFIX), Base64.NO_WRAP)
            require(packed.size > 12) { "row too short" }
            val iv = packed.copyOfRange(0, 12)
            val body = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv)) }
            cipher.doFinal(body).toString(Charsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "vault entry could not be decrypted: ${it.javaClass.simpleName}")
        }.getOrNull()
    }
}
