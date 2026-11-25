package com.openminis.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37119] Tek tuval yerleşimi.
 *
 * Neden test: yerleşim yanlışsa (a) bölmeler üst üste biner — kullanıcı bir
 * bölmeyi görmez ama ajan orada çalıştığını sanar, (b) bir bölme ekranın
 * dışına taşar — dar telefonda sağdaki panel 0 px genişlikte çizilir ve
 * "açtım ama boş" olur. İkisi de sessiz hatadır; sayı üzerinden yakalanır.
 */
class VirtualCanvasTest {

    private val panes = VirtualScreen.Pane.entries

    @Test
    fun tek_bolme_her_modda_tam_ekran() {
        VirtualCanvas.Mode.entries.forEach { mode ->
            val l = VirtualCanvas.layout(360, 800, listOf(panes[0]), panes[0], mode)
            assertEquals("mode=$mode", VirtualCanvas.Mode.SINGLE, l.mode)
            assertEquals(1, l.rects.size)
            assertEquals(360, l.rects[0].second.width)
            assertEquals(800, l.rects[0].second.height)
        }
    }

    @Test
    fun bos_ekran_cokme_yerine_bos_yerlesim_dondurur() {
        val l = VirtualCanvas.layout(360, 800, emptyList(), null, VirtualCanvas.Mode.SPLIT)
        assertTrue(l.rects.isEmpty())
        assertNull(l.hitTest(10, 10))
        assertEquals(360, l.bounds.width)
    }

    @Test
    fun sifir_veya_negatif_olcu_sinir_disi_birakmaz() {
        listOf(0 to 800, 360 to 0, -5 to -5).forEach { (w, h) ->
            val l = VirtualCanvas.layout(w, h, panes, panes[0], VirtualCanvas.Mode.GRID)
            assertTrue("$w x $h", l.rects.isEmpty())
        }
    }

    @Test
    fun single_modda_odakli_bolme_tam_ekran_olur() {
        val l = VirtualCanvas.layout(
            360, 800, panes, VirtualScreen.Pane.TERMINAL, VirtualCanvas.Mode.SINGLE,
        )
        assertEquals(VirtualScreen.Pane.TERMINAL, l.rects[0].first)
    }

    @Test
    fun odak_gorunur_degilse_ilk_bolme_seciliyor() {
        // Odak gizlenmiş bir bölmeye işaret ediyorsa tam ekran çizilecek bir şey
        // yoktur; ilk görünür bölme seçilir (null dönmek boş tuval demek olurdu).
        val l = VirtualCanvas.layout(
            360, 800, listOf(panes[1]), panes[0], VirtualCanvas.Mode.SINGLE,
        )
        assertEquals(panes[1], l.rects[0].first)
    }

    @Test
    fun uc_bolme_split_modda_ikisi_yan_yana_biri_altta() {
        val l = VirtualCanvas.layout(360, 900, panes, panes[0], VirtualCanvas.Mode.SPLIT)
        assertEquals(3, l.rects.size)
        val a = l.rectOf(panes[0])!!
        val b = l.rectOf(panes[1])!!
        val c = l.rectOf(panes[2])!!
        // Yan yana: aynı yükseklik, ayrı yatay aralık.
        assertEquals(a.top, b.top)
        assertEquals(a.height, b.height)
        assertTrue(b.left > a.right)
        // Alttaki: tam genişlik, üst şeridin altında.
        assertEquals(0, c.left)
        assertEquals(360, c.width)
        assertTrue(c.top >= a.bottom)
    }

    @Test
    fun grid_modu_dar_ekranda_splite_duser() {
        // 320 dp'de iki sütun ızgara anlamsız (hücre < 160 dp).
        val l = VirtualCanvas.layout(320, 800, panes, panes[0], VirtualCanvas.Mode.GRID)
        assertEquals(VirtualCanvas.Mode.SPLIT, l.mode)

        val wide = VirtualCanvas.layout(720, 800, panes, panes[0], VirtualCanvas.Mode.GRID)
        assertEquals(VirtualCanvas.Mode.GRID, wide.mode)
        assertEquals(3, wide.rects.size)
    }

    @Test
    fun hicbir_mod_320dp_de_tasmaz_ve_cakismaz() {
        val problems = VirtualCanvas.overflowCases()
        assertTrue("taşma/çakışma: $problems", problems.isEmpty())
    }

    @Test
    fun isabet_testi_dogru_bolmeyi_bulur_ve_sinirda_sasirmaz() {
        val l = VirtualCanvas.layout(360, 900, panes, panes[2], VirtualCanvas.Mode.SPLIT)
        val a = l.rectOf(panes[0])!!
        val b = l.rectOf(panes[1])!!

        // a'nın içinde: a. b'nin sol kenarı: b (bitişik sınır).
        assertEquals(panes[0], l.hitTest(a.left + 1, a.top + 1))
        assertEquals(panes[1], l.hitTest(b.left, b.top + 1))
        // a'nın sağ kenarı **dışarıda** — iki bölme arasındaki 1 px çizgi
        // yanlış bölmeye düşmemeli.
        assertEquals(null, l.hitTest(a.right, a.top + 1))
    }

    @Test
    fun cakisma_olmadigi_icin_ustteki_kurali_sinir_disi_noktada_calisir() {
        val l = VirtualCanvas.layout(360, 800, panes, panes[0], VirtualCanvas.Mode.SPLIT)
        // Dışarıdaki nokta hiçbir bölmeye düşmez.
        assertNull(l.hitTest(-1, 10))
        assertNull(l.hitTest(360, 10))
        assertNull(l.hitTest(10, 800))
    }

    @Test
    fun her_gorunur_bolme_tam_bir_dikdortgen_alir() {
        val l = VirtualCanvas.layout(400, 900, panes, panes[0], VirtualCanvas.Mode.SPLIT)
        assertEquals(panes.size, l.rects.size)
        assertEquals(panes.toSet(), l.rects.map { it.first }.toSet())
        l.rects.forEach { (pane, r) ->
            assertTrue("$pane genişlik", r.width > 0)
            assertTrue("$pane yükseklik", r.height > 0)
        }
    }

    @Test
    fun dp_piksel_cevrimi_tek_yerde_ve_tam_sayi() {
        assertEquals(0, VirtualCanvas.toPx(0, 3f))
        assertEquals(360, VirtualCanvas.toPx(120, 3f))
        assertEquals(120, VirtualCanvas.toPx(120, 1f))
        // Kesirli yoğunluk aşağı yuvarlanır: çizim sınırlarını taşırmamak için.
        assertEquals(179, VirtualCanvas.toPx(120, 1.495f))
        assertNotNull(VirtualCanvas.layout(360, 800, panes, null, VirtualCanvas.Mode.SPLIT))
    }
}
