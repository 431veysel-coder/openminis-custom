package com.openminis.app.provider

import com.openminis.app.network.NetworkUrlRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [MINIS-OZEL 11.1] Providers whose API root contains an account identifier,
 * and how to find that identifier from the key alone.
 *
 * ## The problem this solves
 *
 * "Just paste a key" is the whole promise of a provider preset, and most of
 * them keep it: the base URL is a constant. Cloudflare does not — its OpenAI-
 * compatible path is
 *
 *     https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1
 *
 * so a key on its own produces a 404 and a user who concludes the app is
 * broken. Measured 2026-11-16: `POST .../accounts/<32 hex>/ai/v1/chat/completions`
 * answers **401** (route exists, credential missing), while the same call with
 * a made-up account id answers 404. The route is real; the id is mandatory.
 *
 * Azure is the same shape (`{resource}.openai.azure.com`), which is why this is
 * a small resolver rather than a Cloudflare special case.
 *
 * ## Discovery
 *
 * Cloudflare's `GET /client/v4/accounts` with a bearer token lists the accounts
 * the token can see. One account (the normal case for a personal token) means
 * the id is filled in silently; several means the user picks. A failure is not
 * fatal — the field stays editable, so a restricted token that cannot list
 * accounts still works if the user pastes the id from the dashboard.
 */
object AccountIdDiscovery {

    /** What a provider needs before its base URL can be built. */
    enum class Kind {
        /** Base URL is constant; nothing to discover. */
        NONE,

        /** Cloudflare Workers AI: `/accounts/{id}/ai/v1`. */
        CLOUDFLARE,
    }

    data class AccountRef(val id: String, val name: String)

    data class Discovery(
        val accounts: List<AccountRef> = emptyList(),
        val error: String? = null,
    ) {
        /** The single account, when there is exactly one — safe to auto-fill. */
        val only: AccountRef? get() = accounts.singleOrNull()
    }

    private const val CF_ACCOUNTS = "https://api.cloudflare.com/client/v4/accounts"
    private const val TAG = "AccountIdDiscovery"

    /**
     * Builds the final base URL for [kind] once [accountId] is known.
     *
     * Pure and separate because this string is the difference between a working
     * provider and a 404, and it deserves a test rather than being inlined at a
     * call site that needs a Context to reach.
     */
    internal fun baseUrlFor(kind: Kind, accountId: String): String? = when (kind) {
        Kind.NONE -> null
        Kind.CLOUDFLARE -> accountId.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "https://api.cloudflare.com/client/v4/accounts/$it/ai/v1" }
    }

    /**
     * Cloudflare's account id shape is 32 hex characters. Validating locally
     * turns a typo into "that does not look like an account id" instead of a
     * 404 from a provider the app just told the user to configure.
     */
    internal fun looksLikeCloudflareAccountId(raw: String): Boolean =
        Regex("^[0-9a-f]{32}$", RegexOption.IGNORE_CASE).matches(raw.trim())

    /** Lists the accounts a token can see. Never throws. */
    suspend fun discoverCloudflare(apiKey: String, instanceId: String): Discovery =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Discovery(error = "no API key")
            try {
                NetworkUrlRequest.open(
                    tag = "prov:$instanceId",
                    url = CF_ACCOUNTS,
                    method = "GET",
                    headers = mapOf(
                        "Authorization" to "Bearer $apiKey",
                        "Accept" to "application/json",
                    ),
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 15_000,
                ).use { resp ->
                    val body = runCatching { resp.text() }.getOrDefault("")
                    if (resp.code !in 200..299) {
                        return@use Discovery(error = "HTTP ${resp.code}")
                    }
                    val root = runCatching { JSONObject(body) }.getOrNull()
                        ?: return@use Discovery(error = "could not parse the answer")
                    if (!root.optBoolean("success", true)) {
                        val msg = root.optJSONArray("errors")?.optJSONObject(0)
                            ?.optString("message")?.takeIf { it.isNotBlank() }
                        return@use Discovery(error = msg ?: "Cloudflare reported a failure")
                    }
                    val arr = root.optJSONArray("result")
                    val list = buildList {
                        for (i in 0 until (arr?.length() ?: 0)) {
                            val o = arr!!.optJSONObject(i) ?: continue
                            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                            add(AccountRef(id = id, name = o.optString("name").ifBlank { id.take(8) }))
                        }
                    }
                    Discovery(accounts = list)
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "discovery failed: ${e.javaClass.simpleName}: ${e.message}")
                Discovery(error = e.message ?: e.javaClass.simpleName)
            }
        }
}
