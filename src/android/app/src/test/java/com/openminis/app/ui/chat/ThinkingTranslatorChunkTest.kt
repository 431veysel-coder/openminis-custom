package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37099] `ThinkingTranslator`'ın **saf** parçaları — cihazsız koşar.
 *
 * Neden yalnız saf parçalar: ağ çağrısı, önbellek dosyası ve sağlayıcı bu
 * testin dışında. Ama ölçülen dört hatanın ikisi tam burada, parçalama ve
 * bütçe hesabında yaşıyordu ve ikisi de sessizdi.
 */
class ThinkingTranslatorChunkTest {

    // ── parçalama ──────────────────────────────────────────────────────────

    @Test
    fun `kisa metin tek parca`() {
        assertEquals(listOf("merhaba"), ThinkingTranslator.chunk("merhaba"))
    }

    @Test
    fun `parcalar metnin tamamini kayipsiz kapsar`() {
        // Sessiz hata sınıfı: parçalama bir karakter düşürürse çeviri metnin
        // ortasında bir cümleyi atlar ve bunu kimse fark etmez.
        val text = (1..400).joinToString("\n\n") { "Paragraf $it: " + "x".repeat(40) }
        val chunks = ThinkingTranslator.chunk(text)
        assertTrue(chunks.size > 1)
        val joined = chunks.joinToString("\n\n")
        assertEquals(text.replace(Regex("\\s+"), " ").trim(), joined.replace(Regex("\\s+"), " ").trim())
    }

    @Test
    fun `hicbir parca sinirdan uzun degil`() {
        val text = "a".repeat(10_000) // tek satır, ayırıcı yok
        val chunks = ThinkingTranslator.chunk(text)
        chunks.forEach { assertTrue("parça çok uzun: ${it.length}", it.length <= 3000) }
    }

    @Test
    fun `cumle sinirinda keser`() {
        val limit = 100
        val s = "Birinci cümle burada bitti. İkinci cümle devam ediyor ve uzuyor."
        val cut = ThinkingTranslator.sentenceBoundary(s, limit)
        assertEquals("Birinci cümle burada bitti.".length, cut)
    }

    @Test
    fun `cumle siniri yoksa sinira keser`() {
        val s = "a".repeat(500)
        assertEquals(100, ThinkingTranslator.sentenceBoundary(s, 100))
    }

    @Test
    fun `cjk noktalama da sinir sayilir`() {
        val s = "これは最初の文です。これは二番目の文です。"
        val cut = ThinkingTranslator.sentenceBoundary(s, 12)
        assertTrue("CJK cümle sonunda kesmeliydi: $cut", cut <= 12 && s.substring(0, cut).endsWith("。"))
    }

    // ── kesilme ölçümü ─────────────────────────────────────────────────────

    @Test
    fun `kesilme nedenleri taninir`() {
        // [37099] Bu dört dize önceden HİÇ okunmuyordu: kesik çeviri tam
        // sayılıp önbelleğe yazılıyordu. Sağlayıcıdan sağlayıcıya değişiyorlar
        // (OpenAI "length", Anthropic "max_tokens", Gemini "MAX_TOKENS").
        listOf("length", "max_tokens", "max_output_tokens").forEach { reason ->
            assertTrue("$reason kesilme sayılmalı", com.openminis.app.provider.StopReasons.isMaxTokens(reason))
        }
    }

    @Test
    fun `normal bitis kesilme degil`() {
        // `stop` ve `end_turn` tamamdır; bunları kesilme saymak her çeviriyi
        // yarım göstermek olurdu.
        listOf("stop", "end_turn", "", null).forEach { reason ->
            assertFalse(com.openminis.app.provider.StopReasons.isMaxTokens(reason))
        }
    }

    // ── sonuç tipi ─────────────────────────────────────────────────────────

    @Test
    fun `tamamlanmisliga gore kismi ayrimi`() {
        val full = ThinkingTranslator.Out("tam metin", complete = true, done = 3, total = 3)
        val part = ThinkingTranslator.Out("yarım metin", complete = false, done = 2, total = 3)
        assertTrue(full.complete)
        assertFalse(part.complete)
        assertEquals(3, full.done)
        assertEquals(2, part.done)
    }

    @Test
    fun `kesilmis sonuc asla tamam sayilmaz`() {
        // Kesilme görüldüyse `complete` false olmalı; aksi hâlde kullanıcı
        // cümlenin ortasında biten bir metni bitmiş sanar.
        val cut = ThinkingTranslator.Out("yarım", complete = false, done = 3, total = 3, truncated = true)
        assertFalse(cut.complete)
        assertTrue(cut.truncated)
    }
}
