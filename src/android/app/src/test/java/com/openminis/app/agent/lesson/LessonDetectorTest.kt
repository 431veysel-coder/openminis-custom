package com.openminis.app.agent.lesson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OZEL 37086] Ders sezici testleri. */
class LessonDetectorTest {

    @Test
    fun `tek hata ders uretmez`() {
        // KARAR: bir araç hatası her zaman ders değil (ağ gitmiş olabilir).
        val out = LessonDetector.detect(
            errors = listOf(ToolErrorSignal("shell_execute", "timeout", turnIndex = 1)),
            corrections = emptyList(), loops = emptyList(),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `pencerede tekrar eden hata ders uretir`() {
        val out = LessonDetector.detect(
            errors = listOf(
                ToolErrorSignal("shell_execute", "No such file or directory", 1, "cat a.txt"),
                ToolErrorSignal("shell_execute", "No such file or directory", 2, "cat a.txt"),
            ),
            corrections = emptyList(), loops = emptyList(),
        )
        assertEquals(1, out.size)
        assertTrue(out.single().text.contains("önce `file_read`"))
    }

    @Test
    fun `pencere disindaki tekrar ders degil`() {
        val out = LessonDetector.detect(
            errors = listOf(
                ToolErrorSignal("shell_execute", "timeout", 1),
                ToolErrorSignal("shell_execute", "timeout", 20),
            ),
            corrections = emptyList(), loops = emptyList(),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `kullanici duzeltmesi en yuksek guvenle ders olur`() {
        val out = LessonDetector.detect(
            errors = emptyList(),
            corrections = listOf(CorrectionSignal("Hayır, dosyayı silme — taşı", turnIndex = 3)),
            loops = emptyList(),
        )
        assertEquals(LessonProposal.Confidence.HIGH, out.single().confidence)
    }

    @Test
    fun `duzeltme sayilmayan cumle ders uretmez`() {
        val out = LessonDetector.detect(
            errors = emptyList(),
            corrections = listOf(CorrectionSignal("peki devam et", turnIndex = 3)),
            loops = emptyList(),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `bilinen ders iki kez onerilmez`() {
        // KARAR: aynı ders her turda tekrar önerilirse kullanıcı kartı kapatır
        // ve gerçek yeni dersi kaçırır. Anahtar üretimi iç mantıkla aynı olmalı.
        val first = LessonDetector.detect(
            errors = emptyList(),
            corrections = listOf(CorrectionSignal("Hayır, dosyayı silme", turnIndex = 3)),
            loops = emptyList(),
        )
        assertTrue(first.isNotEmpty())
        val again = LessonDetector.detect(
            errors = emptyList(),
            corrections = listOf(CorrectionSignal("Hayır, dosyayı silme", turnIndex = 5)),
            loops = emptyList(),
            knownKeys = first.map { it.key }.toSet(),
        )
        assertTrue(again.isEmpty())
    }

    @Test
    fun `dongu dusuk guvenle onerilir`() {
        val out = LessonDetector.detect(
            errors = emptyList(), corrections = emptyList(),
            loops = listOf(LoopSignal(turnIndex = 4, repeatedCalls = 5)),
        )
        assertEquals(LessonProposal.Confidence.LOW, out.single().confidence)
    }

    @Test
    fun `JSON hatasi icin somut oneri verilir`() {
        val out = LessonDetector.detect(
            errors = listOf(
                ToolErrorSignal("shell_execute", "JSON parse error", 1),
                ToolErrorSignal("shell_execute", "JSON parse error", 2),
            ),
            corrections = emptyList(), loops = emptyList(),
        )
        assertTrue(out.single().text.contains("file_read"))
    }
}
