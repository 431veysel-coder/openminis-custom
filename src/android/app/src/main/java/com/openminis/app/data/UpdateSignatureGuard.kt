package com.openminis.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.openminis.app.logging.AppLogger
import java.io.File
import java.security.MessageDigest

/**
 * [MINIS-OZEL] Refuses an update APK that is not signed by the same key as the
 * app that is running.
 *
 * ## Why this exists
 *
 * The updater already checks three things before handing an APK to the system
 * installer: the file's SHA-256 against GitHub's reported asset digest, the
 * package name inside the archive, and that its versionCode is newer. All
 * three come from, or describe, the same GitHub account — so they answer
 * "did this download arrive intact and is it the APK the release points at",
 * not "did the person who published that release hold our signing key".
 *
 * If the GitHub account is taken over, an attacker can publish a release with
 * a matching digest and a correct package name. Android itself would then
 * refuse to install it over this app (the OS compares signing certificates for
 * an existing package), so the user is not actually compromised — but they get
 * a bare "App not installed" from the installer, after a 39 MB download, with
 * no explanation and no way to tell a botched release from an attack.
 *
 * This check moves that verdict earlier and makes it legible.
 *
 * ## Why compare against the running app rather than a pinned fingerprint
 *
 * A hardcoded SHA-256 of our certificate would be a constant that has to be
 * updated by hand whenever the key rotates — and the failure mode of forgetting
 * is silent (every update rejected, or the check quietly disabled). Asking the
 * package manager "what is *this* app signed with" cannot drift: it is by
 * definition the key the running binary already trusts. It also works on debug
 * builds, which a pinned production fingerprint would not.
 *
 * ## What this does not protect against
 *
 * An attacker who holds both the GitHub account and our signing key. At that
 * point there is nothing left in the update path to check against — that is a
 * key compromise, and the answer is key rotation plus revocation, not a check
 * inside the app.
 */
internal object UpdateSignatureGuard {

    private const val TAG = "UpdateSig"

    /**
     * Returns a human-readable refusal reason when [apk] is signed by a
     * different key than the running app, or when its signature cannot be read
     * at all. Returns null when the signatures match.
     *
     * "Cannot be read" is a refusal rather than a skip: an archive whose
     * signature we cannot parse is either corrupt or deliberately malformed,
     * and neither is something to hand to the installer.
     */
    fun refusal(context: Context, apk: File): String? {
        val local = localCertSha256(context)
        if (local == null) {
            // Should not happen: an installed app always reports its own
            // signer. If the platform will not tell us, do not invent a
            // refusal — the OS-level check still applies at install time.
            AppLogger.warning(TAG, "cannot read our own signing certificate; not refusing")
            return null
        }
        val archive = archiveCertSha256(context, apk)
        if (archive == null) {
            AppLogger.warning(TAG, "cannot read the signature of ${apk.name}")
            return "signature-unreadable"
        }
        val same = MessageDigest.isEqual(local, archive)
        AppLogger.info(
            TAG,
            "[sig] local=${local.toHex().take(16)}… archive=${archive.toHex().take(16)}… match=$same",
        )
        return if (same) null else "signature-mismatch"
    }

    /** SHA-256 of the signing certificate that signed this installed package. */
    private fun localCertSha256(context: Context): ByteArray? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        firstSignerSha256(info)
    }.onFailure { AppLogger.warning(TAG, "local cert read failed: ${it.message}") }.getOrNull()

    /** SHA-256 of the signing certificate recorded inside the APK file. */
    private fun archiveCertSha256(context: Context, apk: File): ByteArray? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        info?.let { firstSignerSha256(it) }
    }.onFailure { AppLogger.warning(TAG, "archive cert read failed: ${it.message}") }.getOrNull()

    /**
     * The signing certificate of a [android.content.pm.PackageInfo], as SHA-256.
     *
     * APKs may carry several signers during a key rotation; comparing only the
     * first is deliberate. A rotated key installed over the old one keeps the
     * original signer first in the history, and an attacker's certificate will
     * not match it either way.
     */
    private fun firstSignerSha256(info: android.content.pm.PackageInfo): ByteArray? {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return null
        val cert = signers.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
