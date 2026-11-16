package com.openminis.app.provider

import android.content.Context
import org.json.JSONObject

/**
 * [MINIS-OZEL 11.1] Free-tier SHAPE per vendor, plus the local usage counter
 * that turns "free tier" into a number the user can act on.
 *
 * ## Why this file ships no limits
 *
 * Every vendor publishes its limits in a table rendered from live account data
 * (Groq, Gemini, OpenRouter all do), and the numbers move without notice. A
 * limit baked into an APK is wrong within weeks — and a WRONG limit is worse
 * than none, because the user plans around it and then blames the app when the
 * request fails. So the shipped file records what IS stable and was checked
 * against the vendor's own docs:
 *
 *  - which dimensions the vendor measures (RPM / RPD / TPM / TPD / credit / …),
 *  - where the day resets,
 *  - whether the vendor reports a counter we can read (OpenRouter does),
 *  - whether free models are marked in the model id (`:free`).
 *
 * ## Where the numbers come from, then
 *
 * 1. **Read** when the vendor exposes a counter ([Profile.counter]) — always
 *    correct, includes other devices on the same key.
 * 2. **Typed by the user once** from their own dashboard ([UsageLimits]), and
 *    then tracked locally. The app knows every request it sent, so its own
 *    count is exact for this device.
 * 3. **Unknown** otherwise, and the UI says "limit unknown" rather than
 *    guessing.
 */
object FreeTierLimits {

    private const val ASSET = "free-tier-limits.json"

    /** A counter endpoint we can read instead of counting locally. */
    data class Counter(
        val path: String,
        val field: String,
        val auth: String,
    )

    /** The stable shape of one vendor's free tier. */
    data class Profile(
        val provider: String,
        val matchHost: String,
        val docUrl: String,
        val dimensions: List<String>,
        val perModel: Boolean,
        val resetTz: String,
        val freeModelSuffix: String?,
        val counter: Counter?,
        val note: String,
    ) {
        /** Whether this vendor's free allowance can be exhausted by requests/day. */
        val hasDailyRequests: Boolean get() = "rpd" in dimensions
        val hasDailyTokens: Boolean get() = "tpd" in dimensions
        /** Credit-based vendors never reset daily; the UI must not imply they do. */
        val isCreditBased: Boolean get() = "credit" in dimensions
    }

    @Volatile
    private var cache: List<Profile>? = null

    /** Reads the shipped profiles. Never throws. */
    fun profiles(context: Context): List<Profile> {
        cache?.let { return it }
        val parsed = runCatching {
            val text = context.applicationContext.assets
                .open(ASSET).bufferedReader().use { it.readText() }
            val arr = JSONObject(text).optJSONArray("limits") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Profile(
                    provider = o.optString("provider"),
                    matchHost = o.optString("matchHost"),
                    docUrl = o.optString("docUrl"),
                    dimensions = o.optJSONArray("dimensions")?.let { a ->
                        (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
                    } ?: emptyList(),
                    perModel = o.optBoolean("perModel", false),
                    resetTz = o.optString("resetTz", "UTC"),
                    freeModelSuffix = o.optString("freeModelSuffix").takeIf { it.isNotBlank() },
                    counter = o.optJSONObject("counter")?.let { c ->
                        Counter(
                            path = c.optString("path"),
                            field = c.optString("field"),
                            auth = c.optString("auth", "bearer"),
                        )
                    },
                    note = o.optString("note"),
                )
            }
        }.getOrDefault(emptyList())
        cache = parsed
        return parsed
    }

    /**
     * The profile for an instance, matched by HOST rather than by provider
     * type: the whole point is that the same protocol points at very different
     * free tiers, and a custom base URL is what distinguishes them.
     */
    fun profileFor(context: Context, baseUrl: String?): Profile? {
        val host = runCatching {
            java.net.URI(baseUrl?.trim().orEmpty()).host?.lowercase()
        }.getOrNull() ?: return null
        return profiles(context).firstOrNull { p ->
            p.matchHost.isNotBlank() && host.endsWith(p.matchHost)
        }
    }

    // ── the user's numbers ──────────────────────────────────────────────────

    private const val PREFS = "free_tier_limits_prefs"

    /**
     * A limit the user typed in. Kept per instance, because two instances of
     * the same vendor are two accounts with two independent budgets — the same
     * rule the rest of the provider layer follows.
     */
    data class UsageLimits(val requestsPerDay: Int? = null, val tokensPerDay: Long? = null) {
        val isEmpty: Boolean get() = requestsPerDay == null && tokensPerDay == null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun limitsFor(context: Context, instanceId: String): UsageLimits = UsageLimits(
        requestsPerDay = prefs(context)
            .getInt("rpd_$instanceId", -1).takeIf { it >= 0 },
        tokensPerDay = prefs(context)
            .getLong("tpd_$instanceId", -1L).takeIf { it >= 0 },
    )

    fun setLimits(context: Context, instanceId: String, limits: UsageLimits) {
        prefs(context).edit().apply {
            if (limits.requestsPerDay == null) remove("rpd_$instanceId")
            else putInt("rpd_$instanceId", limits.requestsPerDay)
            if (limits.tokensPerDay == null) remove("tpd_$instanceId")
            else putLong("tpd_$instanceId", limits.tokensPerDay)
        }.apply()
    }

    // ── the local counter ───────────────────────────────────────────────────

    /**
     * Requests and tokens this device sent today, per instance.
     *
     * Stored as `<instanceId>|<yyyy-MM-dd in the vendor's reset zone>` → a
     * packed "requests:tokens" pair. Keying by date rather than storing a
     * timestamp + resetting logic means an old day's row is simply a different
     * key: nothing has to run at midnight, and a clock change cannot double
     * count. Rows older than [RETENTION_DAYS] are dropped on write.
     *
     * This counts ONLY what this app sent. A vendor's own dashboard includes
     * every device and every tool using the key, so the two numbers can differ —
     * which is exactly why the profile prefers a counter endpoint when one
     * exists.
     */
    object UsageCounter {

        private const val TAG = "FreeTierUsage"
        private const val STORE = "free_tier_usage_prefs"
        private const val RETENTION_DAYS = 14

        data class Today(val requests: Int, val tokens: Long)

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

        /** The date string a vendor's budget day is keyed by. */
        internal fun dayKey(resetTz: String, nowMillis: Long): String = runCatching {
            val zone = java.time.ZoneId.of(resetTz)
            java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toString()
        }.getOrElse {
            java.time.Instant.ofEpochMilli(nowMillis)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        }

        fun today(context: Context, instanceId: String, resetTz: String): Today {
            val key = "$instanceId|" + dayKey(resetTz, System.currentTimeMillis())
            val raw = prefs(context).getString(key, null) ?: return Today(0, 0L)
            val parts = raw.split(':')
            return Today(
                requests = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                tokens = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
            )
        }

        /**
         * Records one completed request. Called from the request path, so it
         * must be cheap and must never throw — a counter failure may not fail
         * a model call.
         */
        fun record(context: Context, instanceId: String, tokens: Long, resetTz: String) {
            runCatching {
                val p = prefs(context)
                val now = System.currentTimeMillis()
                val key = "$instanceId|" + dayKey(resetTz, now)
                val prev = today(context, instanceId, resetTz)
                p.edit()
                    .putString(key, "${prev.requests + 1}:${prev.tokens + tokens.coerceAtLeast(0)}")
                    .apply()
                prune(p, now)
            }.onFailure {
                android.util.Log.w(TAG, "could not record usage: ${it.javaClass.simpleName}")
            }
        }

        /** Drops rows outside the retention window. Cheap: the set is tiny. */
        private fun prune(p: android.content.SharedPreferences, now: Long) {
            val cutoff = java.time.Instant.ofEpochMilli(now)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                .minusDays(RETENTION_DAYS.toLong()).toString()
            val stale = p.all.keys.filter { key ->
                val day = key.substringAfter('|', "")
                day.isNotEmpty() && day < cutoff
            }
            if (stale.isEmpty()) return
            val ed = p.edit()
            stale.forEach(ed::remove)
            ed.apply()
        }

        /** Every recorded day for an instance, newest first. For the detail row. */
        fun history(context: Context, instanceId: String, limit: Int = 7): List<Pair<String, Today>> =
            prefs(context).all.entries
                .filter { it.key.startsWith("$instanceId|") }
                .mapNotNull { (k, v) ->
                    val parts = v.toString().split(':')
                    val day = k.substringAfter('|')
                    if (day.isEmpty()) null else day to Today(
                        parts.getOrNull(0)?.toIntOrNull() ?: 0,
                        parts.getOrNull(1)?.toLongOrNull() ?: 0L,
                    )
                }
                .sortedByDescending { it.first }
                .take(limit)
    }
}
