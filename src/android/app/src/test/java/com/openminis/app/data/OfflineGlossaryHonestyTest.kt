package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37124] Sözlük motorunun **dürüstlük** sözleşmesi.
 *
 * ## Neden test
 *
 * Arayüz "çevrildi" dememek için `degraded` alanına bakıyor
 * (`ChatAssistantMessageUI`: turuncu "sözlük tabanlı" etiketi). Bu bayrak bir
 * gün `false` dönerse ya da sonuç `complete = true` ile gelirse, etiket
 * **kaybolur** ve cümle çevrilmemiş olduğu hâlde çevrilmiş gibi görünür.
 *
 * `.31`'de tam bu sınıf hata bulundu: `ThinkingTranslator` sözlük sonucunda
 * `degraded` dalını düşürüyordu. Test yoktu, olmadığı için 5 sürüm boyunca
 * görünmedi. Bu dosya o bayrağı çiviliyor.
 */
class OfflineGlossaryHonestyTest {

    private fun pack(vararg pairs: Pair<String, String>) = TranslationPacks.Pack(
        id = "test",
        name = "Test",
        targetLanguage = "Turkish",
        glossary = pairs.toMap(),
    )

    @Test
    fun sozluk_sonucu_her_zaman_degraded() {
        val r = OfflineGlossary.apply(
            "the context window is large",
            pack("context window" to "bağlam penceresi"),
        )
        assertTrue("sözlük çeviri yapmaz, terim değiştirir", r.degraded)
        assertTrue("terim değişmeli", r.replaced > 0)
        assertTrue(r.text.contains("bağlam penceresi"))
    }

    @Test
    fun sozluk_sonucu_asla_tam_sayilmaz() {
        // Cümle yapısı hâlâ kaynak dilde: "tam" demek yalan olurdu.
        val r = OfflineGlossary.apply("context window", pack("context window" to "bağlam penceresi"))
        assertFalse(r.complete)
    }

    @Test
    fun hic_terim_degismezse_bos_sonuc_zincire_devam_ettirir() {
        // Uydurma bir cevap üretmek burada en kötü seçenek: çağıran "motor
        // cevap vermedi" deyip zincirde sonrakini dener.
        val r = OfflineGlossary.apply("hello world", pack("context window" to "bağlam penceresi"))
        assertEquals("", r.text)
        assertEquals(0, r.replaced)
        assertFalse(r.usable)
    }

    @Test
    fun paket_yoksa_bos_doner_cokme_yerine() {
        val r = OfflineGlossary.apply("context window", null)
        assertEquals("", r.text)
        assertFalse(r.usable)
    }

    @Test
    fun uzun_terim_once_degisir() {
        // "context" < "context window": kısa terim önce değişirse uzun terim
        // artık eşleşmez ve çeviri yarım kalır.
        val r = OfflineGlossary.apply(
            "the context window",
            pack("context" to "bağlam", "context window" to "bağlam penceresi"),
        )
        assertEquals("bağlam penceresi tek parça çevrilmeli", "the bağlam penceresi", r.text)
    }

    @Test
    fun kelime_sinirinda_degisir_alt_dize_degil() {
        val r = OfflineGlossary.apply("contextual", pack("context" to "bağlam"))
        assertEquals("alt dize değişmemeli", "", r.text)
    }

    @Test
    fun degisim_sayisi_gercek() {
        // "12 terim değişti" diyebilmek için sayı gerçek olmalı.
        val r = OfflineGlossary.apply("context and context", pack("context" to "bağlam"))
        assertEquals(2, r.replaced)
        assertEquals("bağlam and bağlam", r.text)
    }
}
