package com.openminis.app.provider

import android.content.Context
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.network.NetworkUrlRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [MINIS-OZEL 11.1] Reads a provider's model list and records which models are
 * free, so the picker can mark them.
 *
 * ## Why this is a user action and not automatic
 *
 * Nothing in a saved `ModelEntry` says what a model costs: the provider's price
 * list is a property of the LISTING, and it changes without the model changing.
 * Caching it silently at refresh time would also mean a background request the
 * user did not ask for. So the scan runs when they press it, stores the answer,
 * and the answer carries the time it was read.
 *
 * ## What "free" means here
 *
 * Exactly what the provider itself says, nothing inferred:
 *  - a `pricing` block whose billable fields are all zero (Vercel, models.dev),
 *  - a `tags: ["free"]` marker,
 *  - the vendor's own model-id convention (`:free` for OpenRouter), which needs
 *    no price lookup at all.
 *
 * A model the provider did not describe stays unmarked. "Unknown" is never
 * rendered as "free" — that is the labelling bug that makes a user spend a paid
 * budget believing it was included.
 */
object FreeModelScanner {

    data class ScanResult(
        val freeIds: Set<String>,
        val total: Int,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * Fetches and stores the free-model set for [instance]. Never throws.
     *
     * The URL and headers come from [ProviderConnectionTest] on purpose: a scan
     * that built its own URL would drift from the connection test the moment a
     * vendor's path changes, and the two would then disagree about whether a
     * provider works.
     */
    suspend fun scan(context: Context, instance: ProviderInstance, apiKey: String): ScanResult =
        withContext(Dispatchers.IO) {
            val url = ProviderConnectionTest.modelsUrl(instance, apiKey)
            try {
                NetworkUrlRequest.open(
                    tag = ProviderConnectionTest.routeTag(instance.id),
                    url = url,
                    method = "GET",
                    headers = ProviderConnectionTest.authHeaders(instance, apiKey) +
                        mapOf("Accept" to "application/json"),
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 20_000,
                ).use { response ->
                    if (response.code !in 200..299) {
                        return@use ScanResult(
                            emptySet(),
                            0,
                            "HTTP ${response.code}",
                        )
                    }
                    val body = runCatching { response.text() }.getOrDefault("")
                    val root = runCatching { JSONObject(body) }.getOrElse {
                        return@use ScanResult(emptySet(), 0, "could not parse the answer")
                    }
                    val free = FreeModelCatalog.parseFreeIds(root).toMutableSet()

                    // The id convention needs no price data — apply it after the
                    // price pass so a vendor that marks `:free` only by name is
                    // still covered.
                    val profile = FreeTierLimits.profileFor(context, instance.effectiveBaseURL)
                    profile?.freeModelSuffix?.let { suffix ->
                        collectIds(root).filter { it.endsWith(suffix, ignoreCase = true) }
                            .forEach(free::add)
                    }

                    val total = collectIds(root).size
                    FreeModelCatalog.storeFreeIds(context, instance.id, free)
                    ScanResult(free, total)
                }
            } catch (e: Exception) {
                ScanResult(emptySet(), 0, e.message ?: e.javaClass.simpleName)
            }
        }

    /** Every model id in either payload shape, for the "of N" count. */
    private fun collectIds(root: JSONObject): List<String> {
        val out = ArrayList<String>()
        root.optJSONArray("data")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("id")
                    ?.takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        root.optJSONObject("models")?.let { models ->
            models.keys().forEach { key ->
                val obj = models.optJSONObject(key) ?: return@forEach
                // Gemini lists `models/gemini-...`; strip the prefix so the id
                // matches the entry the user actually has.
                val id = obj.optString("id", key).substringAfterLast("models/")
                if (id.isNotBlank()) out.add(id)
            }
        }
        return out
    }
}
