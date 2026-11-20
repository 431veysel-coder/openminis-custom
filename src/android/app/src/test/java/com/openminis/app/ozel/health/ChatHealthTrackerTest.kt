package com.openminis.app.ozel.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37095] Sağlık çekirdeği testleri — saf JUnit, Android yok.
 *
 * Kural: her test **saati elle** ilerletir. `Thread.sleep` yok, gerçek zaman
 * yok; bu yüzden 9 vaka saniyeler değil milisaniyeler sürer ve hiçbir makinede
 * kararsız kalmaz.
 */
class ChatHealthTrackerTest {

    private var now = 1_000L
    private fun tracker(window: Int = 20) = ChatHealthTracker(clock = { now }, windowSize = window)

    @Test
    fun `requestStarted WAITING ve sayac baslar`() {
        val t = tracker()
        val h = t.requestStarted(atMs = 5_000)
        assertEquals(HealthState.WAITING, h.state)
        assertEquals(5_000L, h.sinceMs)
        // 1.2 saniye geçti, sayı canlı okunur
        assertEquals("1.2s", h.headline(6_200))
    }

    @Test
    fun `firstChunk TTFB olcer`() {
        val t = tracker()
        t.requestStarted(atMs = 1_000)
        val h = t.firstChunk(atMs = 1_820)
        assertEquals(HealthState.STREAMING, h.state)
        assertEquals(820L, h.lastTtfbMs)
        assertEquals(820L, h.p50TtfbMs)
        assertEquals(1, h.samples)
    }

    @Test
    fun `finished toplam sure ve jeton hizi`() {
        val t = tracker()
        t.requestStarted(atMs = 1_000)
        t.firstChunk(atMs = 1_500)
        val h = t.finished(atMs = 3_000, outputTokens = 30)
        assertEquals(HealthState.IDLE, h.state)
        assertEquals(2_000L, h.lastTotalMs)
        assertEquals(15.0, h.tokensPerSec!!, 0.001)   // 30 jeton / 2 saniye
    }

    @Test
    fun `p50 medyandir ortalama degil`() {
        val t = tracker()
        var at = 0L
        listOf(100L, 200L, 300L, 400L, 1_000L).forEach { ttfb ->
            at += 10_000
            t.requestStarted(atMs = at)
            t.firstChunk(atMs = at + ttfb)
        }
        // Aritmetik ortalama 400 olurdu; tek bir yavaş yanıt sayıyı kaydırmamalı.
        assertEquals(300L, t.snapshot().p50TtfbMs)
        assertEquals(5, t.snapshot().samples)
    }

    @Test
    fun `pencere tasinca en eski duser`() {
        val t = tracker(window = 3)
        var at = 0L
        listOf(900L, 900L, 900L, 100L).forEach { ttfb ->
            at += 10_000
            t.requestStarted(atMs = at)
            t.firstChunk(atMs = at + ttfb)
        }
        // Son üç: 900, 900, 100 → medyan 900. Dördüncü ölçüm pencereye girdi.
        assertEquals(3, t.snapshot().samples)
        assertEquals(900L, t.snapshot().p50TtfbMs)
    }

    @Test
    fun `retryScheduled geri cekilmeyi sayar`() {
        val t = tracker()
        val h = t.retryScheduled(attempt = 2, max = 3, backoffMs = 6_000, atMs = 10_000)
        assertEquals(HealthState.RETRYING, h.state)
        assertEquals(2, h.retry)
        assertEquals(3, h.retryMax)
        assertEquals("2/3", h.headline(10_000))
        assertEquals(4_000L, t.retryRemainingMs(12_000))
        assertEquals(0L, t.retryRemainingMs(17_000))
    }

    @Test
    fun `failed etiketi HTTP kodunu tercih eder`() {
        val t = tracker()
        assertEquals("429", t.failed(httpStatus = 429, kind = ErrorKind.RATE_LIMIT).errorLabel)
        val net = t.failed(httpStatus = null, kind = ErrorKind.NETWORK)
        assertEquals("NET", net.errorLabel)
        // Ağ hatası "hata" değil "ulaşılamıyor": chip'in ikonu da farklı.
        assertEquals(HealthState.OFFLINE, net.state)
    }

    @Test
    fun `fmtLatency birimleri`() {
        assertEquals("820ms", fmtLatency(820))
        assertEquals("3.2s", fmtLatency(3_200))
        assertEquals("12s", fmtLatency(12_500))
        assertEquals("0ms", fmtLatency(-5))
    }

    @Test
    fun `reset sayaci sifirlar olcumu korur`() {
        val t = tracker()
        t.requestStarted(atMs = 1_000)
        t.firstChunk(atMs = 1_700)
        t.retryScheduled(attempt = 1, max = 3, backoffMs = 2_000, atMs = 2_000)
        val h = t.reset()
        assertEquals(HealthState.IDLE, h.state)
        assertEquals(0, h.retry)
        assertNull(h.sinceMs)
        // Geçmiş ölçüm duruyor: "dün de yavaştı" sorusunun cevabı silinmez.
        assertEquals(700L, h.p50TtfbMs)
        assertTrue(h.samples == 1)
    }
}
