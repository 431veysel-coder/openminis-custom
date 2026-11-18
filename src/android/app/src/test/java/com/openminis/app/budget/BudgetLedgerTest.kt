package com.openminis.app.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [OZEL 37081] Bütçe testleri. Her biri bir ürün kararı.
 */
class BudgetLedgerTest {

    private val zone = ZoneId.of("Europe/Istanbul")
    private val day = LocalDate.of(2026, 11, 18)
    private fun at(hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun dayStart(d: LocalDate) = d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `uc kademe birbirinden bagimsiz olcer`() {
        val spends = listOf(
            Spend("s1", at(10), 5_000),
            Spend("s1", at(11), 5_000),
            Spend("s2", at(12), 20_000), // başka sohbet
        )
        val st = BudgetLedger.evaluate(BudgetLimits(), spends, "s1", at(13), zone)
        assertEquals(10_000, st.chatUsed)
        assertEquals(30_000, st.dayUsed)
        assertEquals(30_000, st.monthUsed)
    }

    @Test
    fun `gun siniri gecince gunluk tavan sifirlanir`() {
        val prev = dayStart(day.minusDays(1))
        val spends = listOf(Spend("s1", prev, 50_000))
        val st = BudgetLedger.evaluate(
            BudgetLimits(dayTokens = 50_000), spends, "s1", dayStart(day), zone,
        )
        assertEquals(0, st.dayUsed)
        // Aylık tavan aynı ayı gördüğü için hâlâ dolu.
        assertEquals(BudgetVerdict.OK, st.verdict)
        val monthly = BudgetLedger.evaluate(
            BudgetLimits(monthTokens = 50_000), spends, "s1", dayStart(day), zone,
        )
        assertEquals(BudgetVerdict.STOP, monthly.verdict)
        assertEquals("ay", monthly.trippedBy)
    }

    @Test
    fun `esikte uyarir tavanda durdurur`() {
        val spends = listOf(Spend("s1", at(10), 8_500))
        val warn = BudgetLedger.evaluate(BudgetLimits(dayTokens = 10_000), spends, "s1", at(11), zone)
        assertEquals(BudgetVerdict.WARN, warn.verdict)
        assertTrue(BudgetLedger.mayStartTurn(warn))

        val stop = BudgetLedger.evaluate(BudgetLimits(dayTokens = 8_000), spends, "s1", at(11), zone)
        assertEquals(BudgetVerdict.STOP, stop.verdict)
        assertFalse(BudgetLedger.mayStartTurn(stop))
    }

    @Test
    fun `tavanda yeni tur baslamaz`() {
        // KARAR: ortasında durdurmak tutarsız dosya bırakır; başlamamak temiz.
        val st = BudgetLedger.evaluate(BudgetLimits(chatTokens = 100), listOf(Spend("s1", at(9), 100)), "s1", at(10), zone)
        assertFalse(BudgetLedger.mayStartTurn(st))
        assertTrue(st.message().contains("Yeni tur başlatılmadı"))
    }

    @Test
    fun `tahmini harcama ayri raporlanir`() {
        val spends = listOf(
            Spend("s1", at(9), 1_000),
            Spend("s1", at(9), 3_000, estimated = true),
        )
        val st = BudgetLedger.evaluate(BudgetLimits(), spends, "s1", at(10), zone)
        assertEquals(0.75, st.estimatedShare, 0.001)
    }

    @Test
    fun `fiyati bilinmeyen cagri sifir sayilmaz isaretlenir`() {
        // KARAR: bilinmeyen maliyeti 0 göstermek "harcamadım" demek olur.
        val spends = listOf(Spend("s1", at(9), 1_000, usd = null))
        val st = BudgetLedger.evaluate(BudgetLimits(), spends, "s1", at(10), zone)
        assertEquals(1, st.unknownCostSpends)
        val report = BudgetLedger.stopReport(st.copy(verdict = BudgetVerdict.STOP, trippedBy = "gün"), "3 dosya yazıldı")
        assertTrue(report.contains("fiyatı bilinmiyor"))
        assertTrue(report.contains("3 dosya yazıldı"))
    }

    @Test
    fun `tavan yoksa hicbir sey engellenmez`() {
        val st = BudgetLedger.evaluate(BudgetLimits(), listOf(Spend("s1", at(9), 9_000_000)), "s1", at(10), zone)
        assertEquals(BudgetVerdict.OK, st.verdict)
        assertTrue(st.message().isEmpty())
    }
}
