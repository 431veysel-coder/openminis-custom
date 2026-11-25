package com.openminis.app.ui.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37122] Karşılaştırma matrisi yerleşimi.
 *
 * Neden test: tablo yanlış hesaplanırsa (a) sütun 40 dp'ye düşer ve hücreler
 * "boş" görünür — oy veren üye yok sayılır, (b) iddia sütunu kaydırılınca
 * ekrandan çıkar ve satırın hangi iddia olduğu kaybolur. İkisi de sessiz.
 */
class ClaimMatrixLayoutTest {

    private val five = listOf("Ayşe", "Mehmet", "Zeynep", "Can", "Elif")

    @Test
    fun dar_ekranda_sutun_daraltilmaz_kaydirma_devreye_girer() {
        val r = ClaimMatrixLayout.compute(five, availableWidthDp = 320)
        assertTrue("5 üye 320 dp'ye sığmaz", r.needsScroll)
        r.columns.forEach { c ->
            assertTrue("${c.member} sütunu çok dar: ${c.widthDp}", c.widthDp >= ClaimMatrixLayout.MIN_COLUMN_DP)
        }
        assertEquals(ClaimMatrixLayout.CLAIM_COLUMN_DP, r.claimWidthDp)
    }

    @Test
    fun genis_ekranda_kaydirma_yok_ve_tamam_ekrana_sigar() {
        val r = ClaimMatrixLayout.compute(five, availableWidthDp = 1200)
        assertFalse(r.needsScroll)
        assertTrue(r.totalWidthDp <= 1200)
    }

    @Test
    fun sutun_genislikleri_esit_ve_toplam_tutarli() {
        val r = ClaimMatrixLayout.compute(five, 900)
        assertEquals(1, r.columns.map { it.widthDp }.distinct().size)
        assertEquals(r.claimWidthDp + r.columns.sumOf { it.widthDp }, r.totalWidthDp)
    }

    @Test
    fun bos_uye_listesi_cokme_yerine_bos_matris() {
        val r = ClaimMatrixLayout.compute(emptyList(), 320)
        assertTrue(r.columns.isEmpty())
        assertFalse(r.needsScroll)
        assertEquals(ClaimMatrixLayout.CLAIM_COLUMN_DP, r.totalWidthDp)
    }

    @Test
    fun sifir_genislik_sutunu_kucultmez_kaydirmayi_secer() {
        // Ölçüm gelmeden çizim olabilir; sıfır genişlikte "sığdı" demek
        // tabloyu görünmez yapardı.
        val r = ClaimMatrixLayout.compute(five, availableWidthDp = 0)
        assertTrue(r.needsScroll)
        r.columns.forEach { assertTrue(it.widthDp >= ClaimMatrixLayout.MIN_COLUMN_DP) }
    }

    @Test
    fun hucre_sol_kenari_sutun_sirasiyla_artar() {
        val r = ClaimMatrixLayout.compute(five, 320)
        val xs = five.mapNotNull { r.leftOf(it) }
        assertEquals(five.size, xs.size)
        assertEquals(ClaimMatrixLayout.CLAIM_COLUMN_DP, xs.first())
        xs.zipWithNext().forEach { (a, b) -> assertTrue("sol kenarlar artmalı: $a, $b", b > a) }
        assertNull(r.leftOf("Olmayan Üye"))
    }

    @Test
    fun durus_eslemesi_tek_kaynak() {
        assertEquals(ClaimMatrixLayout.Stance.SUPPORT, ClaimMatrixLayout.Stance.of("support"))
        assertEquals("✓", ClaimMatrixLayout.Stance.SUPPORT.mark)
        assertEquals("✗", ClaimMatrixLayout.Stance.OPPOSE.mark)
        assertEquals("?", ClaimMatrixLayout.Stance.UNCERTAIN.mark)
        assertNull(ClaimMatrixLayout.Stance.of("bilinmeyen"))
        assertNull(ClaimMatrixLayout.Stance.of(null))
    }

    @Test
    fun cevap_eslemesi_yaklasik_oldugunu_yazar() {
        // Cevapsız soru: işaret yok (uydurma yok).
        assertEquals("", ClaimMatrixLayout.answerMark(answeredRound = 0, questionRound = 2))
        val mark = ClaimMatrixLayout.answerMark(answeredRound = 3, questionRound = 2)
        assertTrue("yaklaşıklık işareti görünmeli: $mark", mark.startsWith("≈"))
        assertTrue(mark.contains("3"))
    }
}
