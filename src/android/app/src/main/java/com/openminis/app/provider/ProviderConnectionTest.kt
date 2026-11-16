package com.openminis.app.provider

import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.network.NetworkUrlRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [MINIS-OZEL 11.1] "Test connection" for a provider instance.
 *
 * ## Why this exists
 *
 * Adding a provider is a five-field form followed by a silent gamble: the user
 * saves, the Models list stays empty, and nothing says whether the key is
 * wrong, the base URL is wrong, or the host is simply unreachable from this
 * network. Every one of those has a different fix, and all three looked
 * identical. This turns the gamble into a check with a sentence.
 *
 * ## What it does NOT do
 *
 * It does not create entries, write cache files, or touch quota bookkeeping —
 * a diagnostic that changes state is not a diagnostic. It issues one GET
 * against the models endpoint the refresh path already uses, and reports what
 * came back.
 *
 * ## Why the models endpoint
 *
 * It is the cheapest authenticated call every supported vendor answers: no
 * tokens are billed, the response shape is small, and a success proves three
 * things at once (host reachable, TLS fine, credential accepted). Vendors
 * whose `/models` needs no auth (Hugging Face, NVIDIA) still answer 200 with
 * the key present, which is the case that matters here.
 *
 * ## Routing
 *
 * Requests go through [NetworkUrlRequest] with the same `prov:<id>` tag the
 * chat path uses, so a provider routed over Tailscale or a proxy is tested
 * over that route — testing the direct path and reporting success would be
 * actively misleading.
 */
object ProviderConnectionTest {

    /** What a status code means for the user, in the app's own terms. */
    enum class Kind {
        /** 2xx — the endpoint answered and accepted the credential. */
        OK,

        /** 401 — the key is missing or wrong. */
        BAD_KEY,

        /** 403 — key understood but not allowed (permission, region, quota). */
        FORBIDDEN,

        /** 404 — the base URL has no models route; almost always a URL typo. */
        NOT_FOUND,

        /** 429 — accepted, but rate-limited right now. */
        RATE_LIMITED,

        /** Anything else non-2xx. */
        HTTP_ERROR,
    }

    /**
     * The result. [modelCount] is filled when the body parsed as a model list,
     * which is what makes "connected" believable rather than merely "200".
     */
    data class Outcome(
        val kind: Kind,
        val code: Int,
        val elapsedMs: Long,
        val modelCount: Int? = null,
        /** Short server message when it had one worth showing. */
        val detail: String? = null,
        /** Non-null only for [Kind.OK] = false transport failures. */
        val transportError: String? = null,
    ) {
        val ok: Boolean get() = kind == Kind.OK
    }

    // ── URL and header construction (pure, unit-tested) ─────────────────────

    /**
     * The models URL for [instance]. Kept pure and separate because it is the
     * part that goes wrong silently: one missing `/v1` is a 404 the user reads
     * as "provider down".
     */
    internal fun modelsUrl(instance: ProviderInstance, apiKey: String): String {
        val custom = instance.customBaseURL?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val base = custom ?: defaultBase(instance.providerType)
        val withV1 = if (custom == null) {
            base
        } else if (instance.appendV1Suffix && !base.endsWith("/v1")) {
            "$base/v1"
        } else {
            base
        }
        return when (instance.providerType) {
            // Gemini has no OpenAI-shaped /models and authenticates by query
            // parameter, not by header.
            ProviderType.gemini ->
                "$withV1/models?key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
            else -> "$withV1/models"
        }
    }

    private fun defaultBase(type: ProviderType): String = when (type) {
        ProviderType.anthropic -> "https://api.anthropic.com/v1"
        ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
        ProviderType.openAI, ProviderType.openAIResponses -> "https://api.openai.com/v1"
        ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        ProviderType.xAI -> "https://api.x.ai/v1"
        ProviderType.kimiCode -> "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
        ProviderType.githubCopilot -> com.openminis.app.auth.CopilotDeviceFlow.API_BASE
        // A type this build cannot drive has no canonical host; the call will
        // fail with a transport error, which is the honest answer.
        ProviderType.antigravity, ProviderType.unsupported -> "https://api.openai.com/v1"
    }

    /**
     * Auth headers for [instance]. Anthropic takes `x-api-key` and a required
     * version header; everything else here takes a bearer token.
     */
    internal fun authHeaders(instance: ProviderInstance, apiKey: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        when (instance.providerType) {
            ProviderType.anthropic -> {
                headers["x-api-key"] = apiKey
                headers["anthropic-version"] = "2023-06-01"
            }
            ProviderType.gemini -> Unit // key travels in the URL
            else -> if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
        }
        instance.customUserAgent?.trim()?.takeIf { it.isNotEmpty() }?.let {
            headers["User-Agent"] = it
        }
        return headers
    }

    /** Status code → [Kind]. Pure so every branch is testable without a server. */
    internal fun classify(code: Int): Kind = when {
        code in 200..299 -> Kind.OK
        code == 401 -> Kind.BAD_KEY
        code == 403 -> Kind.FORBIDDEN
        code == 404 -> Kind.NOT_FOUND
        code == 429 -> Kind.RATE_LIMITED
        else -> Kind.HTTP_ERROR
    }

    /** Model count from either wiring shape: `{data:[…]}` or Gemini's `{models:[…]}`. */
    internal fun modelCount(body: String): Int? = runCatching {
        val root = JSONObject(body)
        when {
            root.has("data") -> root.optJSONArray("data")?.length()
            root.has("models") -> root.optJSONArray("models")?.length()
            else -> null
        }
    }.getOrNull()

    // ── the call ────────────────────────────────────────────────────────────

    /**
     * Tags a provider's network route. Delegates to the one place that defines
     * the convention — a second copy of `"prov:"` here would drift the day the
     * routing layer changes its naming.
     */
    internal fun routeTag(instanceId: String): String =
        com.openminis.app.network.NetworkRuntime.providerTag(instanceId)

    private const val TIMEOUT_MS = 15_000

    /**
     * Issues the probe. Never throws: a diagnostic that can crash the settings
     * screen is worse than no diagnostic.
     *
     * [apiKey] is passed in rather than read here so the caller can use exactly
     * the credential it is about to save (a just-typed key lives in Compose
     * state before it reaches the encrypted store).
     */
    suspend fun run(instance: ProviderInstance, apiKey: String): Outcome =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val url = modelsUrl(instance, apiKey)
            try {
                NetworkUrlRequest.open(
                    tag = routeTag(instance.id),
                    url = url,
                    method = "GET",
                    headers = authHeaders(instance, apiKey) + mapOf("Accept" to "application/json"),
                    connectTimeoutMs = TIMEOUT_MS,
                    readTimeoutMs = TIMEOUT_MS,
                ).use { response ->
                    val elapsed = System.currentTimeMillis() - started
                    val body = runCatching { response.text() }.getOrDefault("")
                    val kind = classify(response.code)
                    Outcome(
                        kind = kind,
                        code = response.code,
                        elapsedMs = elapsed,
                        modelCount = if (kind == Kind.OK) modelCount(body) else null,
                        detail = if (kind == Kind.OK) null else serverMessage(body),
                    )
                }
            } catch (e: Exception) {
                Outcome(
                    kind = Kind.HTTP_ERROR,
                    code = 0,
                    elapsedMs = System.currentTimeMillis() - started,
                    transportError = e.message ?: e.javaClass.simpleName,
                )
            }
        }

    /**
     * The vendor's own one-line explanation, when the body is a JSON envelope
     * with an `error` field. Truncated: a proxy's HTML error page must not fill
     * a settings row.
     */
    private fun serverMessage(body: String): String? {
        val text = body.trim()
        if (text.isEmpty()) return null
        val fromJson = runCatching {
            val root = JSONObject(text)
            val error = root.optJSONObject("error")
            error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("detail")?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return (fromJson ?: text).take(180).replace(Regex("\\s+"), " ")
    }
}
