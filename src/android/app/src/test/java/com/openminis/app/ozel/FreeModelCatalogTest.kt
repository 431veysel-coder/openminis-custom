package com.openminis.app.ozel

import com.openminis.app.provider.FreeModelCatalog
import com.openminis.app.provider.FreeTierLimits
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 11.1] Free / metered / paid classification, without a network.
 *
 * The whole value of the grouping is that a user can trust it: labelling a paid
 * model "free" makes them burn a budget believing it was included, and labelling
 * a metered model "free" makes a daily cap look like a bug. Both failure modes
 * are decided by pure functions, so they are pinned here.
 */
class FreeModelCatalogTest {

    // ── price of zero ───────────────────────────────────────────────────────

    @Test
    fun `vercel-shaped string prices of zero are free`() {
        val pricing = JSONObject("""{"input":"0","output":"0"}""")
        assertTrue(FreeModelCatalog.isZeroPriced(pricing))
    }

    @Test
    fun `models-dev-shaped numeric prices of zero are free`() {
        assertTrue(FreeModelCatalog.isZeroPriced(JSONObject("""{"input":0,"output":0}""")))
    }

    @Test
    fun `a real price is not free`() {
        assertFalse(
            FreeModelCatalog.isZeroPriced(
                JSONObject("""{"input":"0.0000012","output":"0.000004"}"""),
            ),
        )
    }

    @Test
    fun `a free cache-read price does not make the model free`() {
        // The exact shape that would produce a wrong badge: cache reads are
        // often free while input and output are billed.
        assertFalse(
            FreeModelCatalog.isZeroPriced(
                JSONObject("""{"input":"0.000003","output":"0.000015","input_cache_read":"0"}"""),
            ),
        )
    }

    @Test
    fun `missing, empty and unparseable pricing answers false`() {
        // "Unknown" must never read as "free".
        assertFalse(FreeModelCatalog.isZeroPriced(null))
        assertFalse(FreeModelCatalog.isZeroPriced(JSONObject("{}")))
        assertFalse(FreeModelCatalog.isZeroPriced(JSONObject("""{"input":"ask us"}""")))
    }

    // ── name convention ────────────────────────────────────────────────────

    @Test
    fun `openrouter free suffix is recognised`() {
        assertTrue(FreeModelCatalog.isFreeByName("dots-studio/dots-3-note-preview:free", ":free"))
        assertFalse(FreeModelCatalog.isFreeByName("anthropic/claude-sonnet-4", ":free"))
    }

    @Test
    fun `a suffix that the vendor does not use never matches`() {
        // A provider with no naming convention (suffix null) must not mark
        // anything free by name, however the id is spelled.
        assertFalse(FreeModelCatalog.isFreeByName("something:free", null))
        assertFalse(FreeModelCatalog.isFreeByName("something:free", "  "))
    }

    // ── payload parsing ────────────────────────────────────────────────────

    @Test
    fun `free ids come from pricing and from the free tag`() {
        val payload = JSONObject(
            """
            {"data":[
              {"id":"vendor/a","pricing":{"input":"0","output":"0"}},
              {"id":"vendor/b","pricing":{"input":"1","output":"2"},"tags":["free"]},
              {"id":"vendor/c","pricing":{"input":"1","output":"2"},"tags":["reasoning"]}
            ]}
            """.trimIndent(),
        )
        assertEquals(setOf("vendor/a", "vendor/b"), FreeModelCatalog.parseFreeIds(payload))
    }

    @Test
    fun `a payload with no recognisable shape yields nothing`() {
        assertTrue(FreeModelCatalog.parseFreeIds(JSONObject("""{"object":"list"}""")).isEmpty())
        assertTrue(FreeModelCatalog.parseFreeIds(JSONObject("""{"data":[]}""")).isEmpty())
    }

    // ── grouping ───────────────────────────────────────────────────────────

    private fun profile(
        dims: List<String>,
        perModel: Boolean = false,
        extraSuffix: String? = null,
    ) = FreeTierLimits.Profile(
        provider = "test",
        matchHost = "example.test",
        docUrl = "",
        dimensions = dims,
        perModel = perModel,
        resetTz = "UTC",
        freeModelSuffix = extraSuffix,
        counter = null,
        note = "",
    )

    @Test
    fun `zero-priced and suffixed models land in the free group`() {
        val (free, metered, rest) = FreeModelCatalog.partition(
            modelIds = listOf("a", "b", "c"),
            freeIds = setOf("a"),
            freeSuffix = null,
            profile = profile(emptyList()),
            hasConfiguredLimit = false,
        )
        assertEquals(listOf("a"), free)
        assertEquals(emptyList<String>(), metered)
        assertEquals(listOf("b", "c"), rest)
    }

    @Test
    fun `a metered vendor groups the rest only when a limit is configured`() {
        // Without a configured limit there is nothing to show against, so the
        // models stay in the ordinary list rather than in a group that would
        // display a question mark.
        val withLimit = FreeModelCatalog.partition(
            modelIds = listOf("a", "b"),
            freeIds = setOf("a"),
            freeSuffix = null,
            profile = profile(listOf("rpd", "rpm")),
            hasConfiguredLimit = true,
        )
        assertEquals(listOf("b"), withLimit.second)

        val withoutLimit = FreeModelCatalog.partition(
            modelIds = listOf("a", "b"),
            freeIds = setOf("a"),
            freeSuffix = null,
            profile = profile(listOf("rpd", "rpm")),
            hasConfiguredLimit = false,
        )
        assertEquals(emptyList<String>(), withoutLimit.second)
        assertEquals(listOf("b"), withoutLimit.third)
    }

    @Test
    fun `a vendor with no allowance dimension never produces a metered group`() {
        // DeepSeek declares only concurrency: there is no daily budget to show,
        // so its models must not appear as "metered".
        val (_, metered, _) = FreeModelCatalog.partition(
            modelIds = listOf("deepseek-chat"),
            freeIds = emptySet(),
            freeSuffix = null,
            profile = profile(listOf("concurrency")),
            hasConfiguredLimit = true,
        )
        assertTrue(metered.isEmpty())
    }

    @Test
    fun `profile flags match the dimensions the vendor actually measures`() {
        val groq = profile(listOf("rpm", "rpd", "tpm", "tpd"))
        assertTrue(groq.hasDailyRequests)
        assertTrue(groq.hasDailyTokens)
        assertFalse(groq.isCreditBased)

        val vercel = profile(listOf("credit"))
        assertTrue(vercel.isCreditBased)
        assertFalse("a monthly credit is not a daily reset", vercel.hasDailyRequests)
    }

    // ── reset day ──────────────────────────────────────────────────────────

    @Test
    fun `the counter day follows the vendor's reset zone, not the device's`() {
        // 2026-11-16 20:00 UTC is already 2026-11-17 in UTC+4 and still
        // 2026-11-16 in Los Angeles — the same instant belongs to different
        // budget days, which is exactly why the zone is per vendor.
        val instant = java.time.Instant.parse("2026-11-16T20:00:00Z").toEpochMilli()
        assertEquals("2026-11-16", FreeTierLimits.UsageCounter.dayKey("UTC", instant))
        assertEquals(
            "2026-11-16",
            FreeTierLimits.UsageCounter.dayKey("America/Los_Angeles", instant),
        )
        assertEquals("2026-11-17", FreeTierLimits.UsageCounter.dayKey("Asia/Dubai", instant))
    }

    @Test
    fun `an unknown zone falls back to UTC instead of throwing`() {
        val instant = java.time.Instant.parse("2026-11-16T20:00:00Z").toEpochMilli()
        assertEquals(
            "2026-11-16",
            FreeTierLimits.UsageCounter.dayKey("Not/AZone", instant),
        )
    }
}
