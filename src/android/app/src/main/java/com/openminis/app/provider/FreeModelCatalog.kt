package com.openminis.app.provider

import android.content.Context
import org.json.JSONObject

/**
 * [MINIS-OZEL 11.1] Which models are free, per provider instance — and the
 * grouping the model picker draws from it.
 *
 * ## Why this is not a Vercel feature
 *
 * "Free tier" means three different things depending on the vendor, and all
 * three are handled here:
 *
 *  1. **A price of zero.** Vercel's gateway returns a `pricing` block on every
 *     model and tags some of them `free`; models.dev carries `cost.input` /
 *     `cost.output` for 225 providers. Both say "$0" in their own words.
 *  2. **A name convention.** OpenRouter marks free models with a `:free`
 *     suffix in the model id — no price lookup needed, the id IS the answer.
 *  3. **A metered allowance that is not a price.** Gemini, Groq, Cerebras: the
 *     model costs money but a daily/hourly budget is included. Those models are
 *     NOT free in the sense above, and calling them free would be a lie; they
 *     are surfaced as "metered" with the counter from [FreeTierLimits].
 *
 * The catalog therefore answers two separate questions — [isZeroPriced] and
 * [isMetered] — and the picker shows them as two groups. Collapsing them into
 * one "free" badge is the mistake this file exists to avoid.
 *
 * ## Storage
 *
 * Zero-price ids are learned from whichever payload described them and cached
 * per instance id in SharedPreferences. Not in the model database: a price is
 * metadata about a listing, not a property of the model the user configured,
 * and a cached price must never look like a user-edited setting.
 */
object FreeModelCatalog {

    private const val PREFS = "free_models_prefs"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(instanceId: String) = "free_$instanceId"

    /** Model ids known to be zero-priced for this instance. */
    fun freeIds(context: Context, instanceId: String): Set<String> =
        prefs(context).getStringSet(key(instanceId), emptySet())?.toSet() ?: emptySet()

    fun storeFreeIds(context: Context, instanceId: String, ids: Set<String>) {
        prefs(context).edit().putStringSet(key(instanceId), ids).apply()
    }

    fun clear(context: Context, instanceId: String) {
        prefs(context).edit().remove(key(instanceId)).apply()
    }

    // ── pure detection (unit-tested) ────────────────────────────────────────

    /**
     * True when [modelId] is free by NAME: the vendor's own suffix convention.
     * Only used when the profile says the vendor has one, so a model that
     * happens to end in `:free` at a vendor that does not use the convention
     * cannot be mislabelled.
     */
    internal fun isFreeByName(modelId: String, suffix: String?): Boolean {
        if (suffix.isNullOrBlank()) return false
        return modelId.trim().endsWith(suffix, ignoreCase = true)
    }

    /**
     * True when a model is free by PRICE.
     *
     * [pricing] is whatever JSON block the vendor returned; the field names
     * differ (Vercel: `input`/`output` as strings, models.dev: numbers), so the
     * parser accepts both and treats "present and parses to 0" as free. A block
     * that is missing, empty or unparseable answers FALSE — never "free by
     * default", because a false "free" label is what makes a user burn a paid
     * budget believing it was included.
     */
    internal fun isZeroPriced(pricing: JSONObject?): Boolean {
        if (pricing == null) return false
        val keys = pricing.keys().asSequence().toList()
        if (keys.isEmpty()) return false
        // A cache-read price of 0 with a real input price is not a free model.
        val billable = keys.filterNot { it.contains("cache", ignoreCase = true) }
        if (billable.isEmpty()) return false
        var sawNumber = false
        for (k in billable) {
            val raw = pricing.opt(k)
            val value = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> null
            } ?: continue
            sawNumber = true
            if (value != 0.0) return false
        }
        return sawNumber
    }

    /**
     * Pulls the free ids out of a models payload: either a Vercel-shaped list
     * (`data[]` with `id`, `pricing`, `tags`) or a models.dev-shaped map of
     * provider → models with `cost`.
     *
     * Written as one tolerant pass rather than two parsers because both shapes
     * appear in this codebase and a third will eventually show up; anything it
     * does not recognise is simply not marked free.
     */
    internal fun parseFreeIds(root: JSONObject): Set<String> {
        val out = LinkedHashSet<String>()
        root.optJSONArray("data")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val id = m.optString("id").takeIf { it.isNotBlank() } ?: continue
                val tags = m.optJSONArray("tags")?.let { t ->
                    (0 until t.length()).mapNotNull { t.optString(it).takeIf(String::isNotBlank) }
                } ?: emptyList()
                if (tags.any { it.equals("free", ignoreCase = true) } ||
                    isZeroPriced(m.optJSONObject("pricing"))
                ) {
                    out.add(id)
                }
            }
        }
        root.optJSONObject("models")?.let { models ->
            models.keys().forEach { id ->
                val m = models.optJSONObject(id) ?: return@forEach
                if (isZeroPriced(m.optJSONObject("cost"))) out.add(id)
            }
        }
        return out
    }

    // ── grouping for the picker ─────────────────────────────────────────────

    /**
     * One group the picker can draw. [metered] is not "free" — the model is
     * billed but an allowance is included, and the row shows the remaining
     * count instead of a "free" badge.
     */
    data class Group(
        val title: Int,
        val modelIds: List<String>,
        val metered: Boolean,
    )

    /**
     * Splits [modelIds] into free / metered / everything else, preserving the
     * caller's order inside each group (the caller already sorted by whatever
     * the user last used).
     *
     * A model is metered when its vendor profile declares daily or monthly
     * allowance dimensions AND the instance has a limit configured; without a
     * configured limit there is nothing to show, so it stays in the normal
     * list rather than in a group whose only content would be a question mark.
     */
    internal fun partition(
        modelIds: List<String>,
        freeIds: Set<String>,
        freeSuffix: String?,
        profile: FreeTierLimits.Profile?,
        hasConfiguredLimit: Boolean,
    ): Triple<List<String>, List<String>, List<String>> {
        val free = ArrayList<String>()
        val metered = ArrayList<String>()
        val rest = ArrayList<String>()
        val meteredProfile = profile != null &&
            hasConfiguredLimit &&
            (profile.hasDailyRequests || profile.hasDailyTokens || profile.isCreditBased)
        for (id in modelIds) {
            when {
                id in freeIds || isFreeByName(id, profile?.freeModelSuffix ?: freeSuffix) -> free.add(id)
                meteredProfile -> metered.add(id)
                else -> rest.add(id)
            }
        }
        return Triple(free, metered, rest)
    }
}
