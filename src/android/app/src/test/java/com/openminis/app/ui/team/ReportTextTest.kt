package com.openminis.app.ui.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37099k · §8.2] Rapor biçimlendirmesi — cihazsız koşar.
 *
 * Neden test: ayrıştırıcı raporun **anlamını** değiştirmemeli. Yanlış bir
 * kural (ör. `-` ile başlayan cümleyi madde sanmak) metni bozar; kullanıcı
 * da modelin yazdığını değil, ayrıştırıcının yorumunu okur.
 */
class ReportTextTest {

    @Test
    fun `baslik seviyeleri`() {
        val lines = ReportText.lines("# Bir\n## İki\n### Üç")
        assertEquals(listOf(1, 2, 3), lines.map { it.level })
        assertEquals(listOf("Bir", "İki", "Üç"), lines.map { it.text })
    }

    @Test
    fun `madde ve numarali satir ayrilir`() {
        val lines = ReportText.lines("- bir\n* iki\n1. üç\n2) dört")
        assertEquals(ReportText.Kind.BULLET, lines[0].kind)
        assertEquals(ReportText.Kind.BULLET, lines[1].kind)
        assertEquals(ReportText.Kind.NUMBERED, lines[2].kind)
        assertEquals("üç", lines[2].text)
        assertEquals(ReportText.Kind.NUMBERED, lines[3].kind)
    }

    @Test
    fun `tire ile baslayan normal cumle madde olmaz`() {
        // "-5 derece" bir madde sanılmamalı: boşluk yoksa madde değil.
        val lines = ReportText.lines("-5 derecede çalışır")
        assertEquals(ReportText.Kind.BODY, lines.single().kind)
        assertEquals("-5 derecede çalışır", lines.single().text)
    }

    @Test
    fun `duz metin oldugu gibi kalir`() {
        val text = "Bu bir cümle. İkinci cümle."
        assertEquals(text, ReportText.lines(text).single().text)
    }

    @Test
    fun `ayirici cizgi`() {
        assertEquals(ReportText.Kind.RULE, ReportText.lines("---").single().kind)
        assertEquals(ReportText.Kind.RULE, ReportText.lines("***").single().kind)
    }

    @Test
    fun `atifi olan satir bozulmaz`() {
        val text = "Testler geçti [E1], madde C3 [C3]."
        assertTrue(ReportText.lines(text).single().text.contains("[E1]"))
        // Biçim eklenir ama metin bozulmaz.
        assertEquals(text, linkifyCitations(text).text)
    }
}
