package com.openminis.app.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OZEL 37083] Plan önizleme testleri. */
class PlanGateTest {

    private val t = PlanThresholds()

    private fun est(
        minutes: Int = 1,
        tokens: Long = 5_000,
        irreversible: Int = 0,
        deviceOrNet: Boolean = false,
        hash: String = "",
    ) = RunEstimate(
        minutes = minutes, tokens = tokens, irreversibleOps = irreversible,
        touchesDeviceOrNetwork = deviceOrNet, planHash = hash,
        steps = listOf("3 dosya düzeltilecek", "testler koşacak"),
    )

    @Test
    fun `kucuk is kart gostermez`() {
        // KARAR: 5 saniyelik iş için kart, özelliği ilk günde kapattırır.
        assertNull(PlanGate.shouldPreview(est(), t))
    }

    @Test
    fun `uzun is kart gosterir`() {
        assertTrue(PlanGate.shouldPreview(est(minutes = 5), t)!!.contains("dakika"))
    }

    @Test
    fun `cok token harcayan is kart gosterir`() {
        assertTrue(PlanGate.shouldPreview(est(tokens = 60_000), t)!!.contains("token"))
    }

    @Test
    fun `geri donusssuz islem esigi yariya indirir`() {
        // KARAR: geri alınamaz iş varsa daha erken sormak doğru.
        assertNull(PlanGate.shouldPreview(est(minutes = 2), t))
        assertTrue(PlanGate.shouldPreview(est(minutes = 2, irreversible = 1), t) != null)
    }

    @Test
    fun `onaylanan plan tekrar sorulmaz`() {
        val e = est(minutes = 10, hash = "h1")
        assertNull(PlanGate.shouldPreview(e, t, alreadyApprovedHashes = setOf("h1")))
        assertTrue(PlanGate.shouldPreview(e, t, alreadyApprovedHashes = setOf("h2")) != null)
    }

    @Test
    fun `kart geri donusssuz islemi uyarir`() {
        val card = PlanGate.card(est(minutes = 4, irreversible = 2), "4 dakika sürecek")
        assertTrue(card.warning!!.contains("geri alınamaz"))
        assertEquals("Böyle devam et", card.approveLabel)
        assertTrue(card.bullets.any { it.contains("dosya") })
    }

    @Test
    fun `kart planin kendi adimlarini tasir`() {
        val card = PlanGate.card(est(minutes = 4), "4 dakika sürecek")
        assertEquals("Plan: 3 dosya düzeltilecek", card.title)
        assertTrue(card.bullets.any { it.contains("testler koşacak") })
    }
}
