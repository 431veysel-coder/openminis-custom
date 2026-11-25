package com.openminis.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37118] `screen` aracının **saf** çözümleyicisi.
 *
 * Neden test: araç sonucu **modele** gider. Yanlış bir çözümleme iki zarar
 * üretir: (a) eksik argümanda susan bir araç modeli aynı çağrıyı tekrar
 * denemeye iter (tur yakar), (b) bölme adı sessizce `null` olursa iş yanlış
 * bölmeye yazılır — kullanıcı "terminalim neden notların taslağını taşıyor"
 * der.
 *
 * Bu yüzden burada üç sınıf çiviliyoruz: bölme zorunluluğu, argüman
 * zorunluluğu, negatif değerlerin kırpılması.
 */
class ScreenRequestTest {

    private fun parse(vararg pairs: Pair<String, Any?>): ScreenRequest =
        ScreenRequest.parse(pairs.toMap())

    @Test
    fun state_ve_capture_bolmesiz_calisir() {
        assertTrue(parse("action" to "read") is ScreenRequest.Stateless)
        assertTrue(parse("action" to "capture") is ScreenRequest.Stateless)
    }

    @Test
    fun capture_bolme_verilirse_tek_bolmeye_daralir() {
        val r = parse("action" to "capture", "pane" to "browser")
        assertTrue(r is ScreenRequest.PaneAction)
        assertEquals(VirtualScreen.Pane.BROWSER, (r as ScreenRequest.PaneAction).pane)
    }

    @Test
    fun bolme_gerektiren_eylemler_bolmesiz_reddedilir_ve_ne_yapilacagini_soyler() {
        listOf("open", "focus", "hide", "scroll", "type", "click", "navigate").forEach { a ->
            val r = parse("action" to a)
            assertTrue("$a bölmesiz geçmemeli", r is ScreenRequest.Invalid)
            // Mesaj modele ne yapacağını söylemeli: "hata" değil "pane ver".
            assertTrue((r as ScreenRequest.Invalid).message.contains("pane"))
        }
    }

    @Test
    fun bilinmeyen_eylem_eylemin_kendisini_anar() {
        val r = parse("action" to "tikla")
        assertTrue(r is ScreenRequest.Invalid)
        assertTrue((r as ScreenRequest.Invalid).message.contains("tikla"))
    }

    @Test
    fun eksik_eylem_soylenir() {
        val r = parse("pane" to "browser")
        assertTrue(r is ScreenRequest.Invalid)
        assertTrue((r as ScreenRequest.Invalid).message.contains("action"))
    }

    @Test
    fun bilinmeyen_bolme_gecerli_listeyi_gostermez_ama_adi_anar() {
        val r = parse("action" to "open", "pane" to "terminalim")
        assertTrue(r is ScreenRequest.Invalid)
        assertTrue((r as ScreenRequest.Invalid).message.contains("terminalim"))
    }

    @Test
    fun her_eylem_kendi_zorunlu_argumanini_ister() {
        val cases = mapOf(
            "scroll" to "scroll_y",
            "type" to "text",
            "click" to "x",
            "navigate" to "location",
        )
        cases.forEach { (action, arg) ->
            val r = parse("action" to action, "pane" to "browser")
            assertTrue("$action argümansız geçmemeli", r is ScreenRequest.Invalid)
            assertTrue(
                "$action mesajı '$arg' demeli, gelen: ${(r as ScreenRequest.Invalid).message}",
                r.message.contains(arg),
            )
        }
    }

    @Test
    fun tiklama_iki_koordinat_da_ister() {
        val onlyX = parse("action" to "click", "pane" to "browser", "x" to 10)
        assertTrue(onlyX is ScreenRequest.Invalid)
        assertTrue((onlyX as ScreenRequest.Invalid).message.contains("y"))

        val both = parse("action" to "click", "pane" to "browser", "x" to 10, "y" to 20)
        assertTrue(both is ScreenRequest.PaneAction)
        assertEquals(10, (both as ScreenRequest.PaneAction).x)
    }

    @Test
    fun negatif_kaydirma_ve_koordinat_sifira_kirpilir_reddedilmez() {
        // Reddetmek modeli aynı şeyi farklı sayıyla denemeye iter; kırpmak
        // kaydı bozmadan devam eder.
        assertEquals(0, ScreenRequest.clamp(-5))
        assertEquals(0, ScreenRequest.clamp(-1))
        assertEquals(120, ScreenRequest.clamp(120))
        assertEquals(null, ScreenRequest.clamp(null))
    }

    @Test
    fun sayisal_argumanlar_metinden_de_okunur() {
        // Bazı sağlayıcılar tam sayıyı string olarak gönderiyor (bilinen sınıf:
        // `coerceNestedJsonStrings`in kardeşi). Metin sayı kabul edilmeli.
        val r = parse("action" to "scroll", "pane" to "browser", "scroll_y" to "42")
        assertTrue(r is ScreenRequest.PaneAction)
        assertEquals(42, (r as ScreenRequest.PaneAction).scrollY)
    }

    @Test
    fun bos_metin_kirpilir_ve_kayda_yazilmaz() {
        assertEquals(null, ScreenRequest.cleanText("   "))
        assertEquals(null, ScreenRequest.cleanText(null))
        assertEquals("https://x", ScreenRequest.cleanText("  https://x  "))
    }

    @Test
    fun open_adressiz_de_gecerlidir_cunku_adres_sonradan_verilebilir() {
        val r = parse("action" to "open", "pane" to "terminal")
        assertTrue(r is ScreenRequest.PaneAction)
        assertEquals(null, (r as ScreenRequest.PaneAction).location)
    }
}
