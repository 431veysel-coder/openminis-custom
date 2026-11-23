package com.openminis.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37099] Sanal ekranın **saf** kuralları — cihazsız koşar.
 *
 * Neden önemli: bu cihazda JVM başlamıyor, yani Compose tarafı hiçbir kapıdan
 * geçmiyor. Ekranın kararları (hangi bölme çizilir, durum nasıl saklanır, A ile
 * B arasındaki izin farkı) burada olduğu için test edilebiliyor.
 */
class VirtualScreenTest {

    private val S = VirtualScreen.State()

    // ── düzen ──────────────────────────────────────────────────────────────

    @Test
    fun `varsayilan uc bolme acik`() {
        assertEquals(3, S.visiblePanes().size)
        assertEquals(VirtualScreen.Pane.BROWSER, S.focus)
    }

    @Test
    fun `bos ekran varsayilan degil`() {
        // `State()` üç bölme açar; `empty()` hiç. Karışırsa "hepsini kapat"
        // ilk açılışta üç panel yakalatır.
        val empty = VirtualScreen.empty()
        assertTrue(empty.visiblePanes().isEmpty())
        assertNull(empty.focus)
        assertFalse(S.visiblePanes().isEmpty())
    }

    @Test
    fun `gizlenen bolme odagi devreder`() {
        val s = VirtualScreen.hide(S, VirtualScreen.Pane.BROWSER)
        assertFalse(s.pane(VirtualScreen.Pane.BROWSER)!!.visible)
        assertFalse(s.focus == VirtualScreen.Pane.BROWSER)
        // Odak görünür bir bölmeye geçmeli, null'a düşmemeli.
        assertTrue(s.visiblePanes().any { it.pane == s.focus })
    }

    @Test
    fun `gizli bolme odak olamaz`() {
        val s = VirtualScreen.hide(S, VirtualScreen.Pane.TERMINAL)
        val focused = VirtualScreen.focus(s, VirtualScreen.Pane.TERMINAL)
        assertEquals(s.focus, focused.focus) // istek yok sayıldı
    }

    @Test
    fun `yeniden siralama bilinmeyen adi yok sayar`() {
        val s = VirtualScreen.reorder(S, listOf(VirtualScreen.Pane.TERMINAL, VirtualScreen.Pane.BROWSER))
        assertEquals(
            listOf(VirtualScreen.Pane.TERMINAL, VirtualScreen.Pane.BROWSER, VirtualScreen.Pane.NOTES),
            s.panes.map { it.pane },
        )
    }

    @Test
    fun `yeniden siralama bolme kaybettirmez`() {
        val s = VirtualScreen.reorder(S, listOf(VirtualScreen.Pane.NOTES))
        assertEquals(3, s.panes.size)
    }

    // ── yakalama bütçesi ───────────────────────────────────────────────────

    @Test
    fun `bos ekranda yakalama yok`() {
        assertTrue(VirtualScreen.needsCapture(VirtualScreen.empty(), now = 10_000).isEmpty())
    }

    @Test
    fun `yakalama araligi korunur`() {
        // 250 ms'den sık yakalamak pili yakar; hiç yakalamamak ajanı kör bırakır.
        val after = VirtualScreen.markCaptured(S, 1_000)
        assertTrue(VirtualScreen.needsCapture(after, now = 1_100).isEmpty())
        assertEquals(3, VirtualScreen.needsCapture(after, now = 1_400).size)
    }

    @Test
    fun `yalniz gorunur bolmeler yakalanir`() {
        val s = VirtualScreen.hide(S, VirtualScreen.Pane.NOTES)
        assertEquals(
            listOf(VirtualScreen.Pane.BROWSER, VirtualScreen.Pane.TERMINAL),
            VirtualScreen.needsCapture(s, now = 0),
        )
    }

    // ── A / B eylem izni ───────────────────────────────────────────────────

    @Test
    fun `sanal ekranda tiklama bedelsiz`() {
        // Ajanın kendi tarayıcısında tıklaması kullanıcının ekranını değiştirmez.
        assertEquals(
            VirtualScreen.Cost.SILENT,
            VirtualScreen.cost(VirtualScreen.Action.CLICK, VirtualScreen.Target.VIRTUAL),
        )
    }

    @Test
    fun `gercek ekranda tiklama onay ister`() {
        // B: yanlış dokunuş başka uygulamada geri alınamaz bir işlem yapabilir.
        assertEquals(
            VirtualScreen.Cost.CONFIRM,
            VirtualScreen.cost(VirtualScreen.Action.CLICK, VirtualScreen.Target.DEVICE),
        )
    }

    @Test
    fun `okumak iki tarafta da bedelsiz`() {
        listOf(VirtualScreen.Target.VIRTUAL, VirtualScreen.Target.DEVICE).forEach { t ->
            assertEquals(VirtualScreen.Cost.SILENT, VirtualScreen.cost(VirtualScreen.Action.READ, t))
        }
    }

    @Test
    fun `gercek ekranda kaydirma onay ister`() {
        assertEquals(
            VirtualScreen.Cost.ASK,
            VirtualScreen.cost(VirtualScreen.Action.SCROLL, VirtualScreen.Target.DEVICE),
        )
    }

    // ── durum kaydı ────────────────────────────────────────────────────────

    @Test
    fun `durum gidis donus kayipsiz`() {
        val s = VirtualScreen.update(S, VirtualScreen.Pane.BROWSER) {
            it.copy(location = "https://ornek.test/a|b", scrollY = 420, draft = "taslak~metin")
        }
        val back = VirtualScreen.decode(VirtualScreen.encode(s))
        val p = back.pane(VirtualScreen.Pane.BROWSER)!!
        // Ayırıcı karakterler metnin içinde geçse bile bozulmamalı.
        assertEquals("https://ornek.test/a|b", p.location)
        assertEquals(420, p.scrollY)
        assertEquals("taslak~metin", p.draft)
        assertEquals(s.focus, back.focus)
    }

    @Test
    fun `bos kayit varsayilana doner`() {
        assertEquals(VirtualScreen.defaultPanes().size, VirtualScreen.decode("").panes.size)
    }

    @Test
    fun `bilinmeyen bolme atlanir`() {
        // Eski sürümden gelen kayıt: "editor" artık yok.
        val s = VirtualScreen.decode("editor|1|/x|0|~~editor")
        assertEquals(3, s.panes.size)
        assertTrue(s.visiblePanes().isEmpty()) // bilinmeyen çizilmedi, diğerleri yoktu
    }

    @Test
    fun `yeniden siralama kayittan sonra korunur`() {
        // [37099b] Ölçülen hata: reorder bellekte çalışıyor, encode yazıyor,
        // ama decode sonunda enum sırasına eziyordu. Ajan her açılışta
        // düzenini kaybediyordu.
        val s = VirtualScreen.reorder(S, listOf(VirtualScreen.Pane.TERMINAL, VirtualScreen.Pane.BROWSER))
        val back = VirtualScreen.decode(VirtualScreen.encode(s))
        assertEquals(
            listOf(VirtualScreen.Pane.TERMINAL, VirtualScreen.Pane.BROWSER, VirtualScreen.Pane.NOTES),
            back.panes.map { it.pane },
        )
    }

    @Test
    fun `eski kayit sirasiz da okunur`() {
        // Biçim değişikliği göç istemez: sıra alanı olmayan kayıt enum sırasına düşer.
        val legacy = "terminal|1||0|~browser|1||0|~~terminal"
        assertEquals(
            listOf(VirtualScreen.Pane.BROWSER, VirtualScreen.Pane.TERMINAL),
            VirtualScreen.decode(legacy).visiblePanes().map { it.pane },
        )
    }

    @Test
    fun `hepsi kapali kaydi korunur`() {
        // "Hepsini kapat" geçerli bir durum; kayıttan sonra varsayılana
        // dönmek kullanıcının kararını silmek olurdu.
        val empty = VirtualScreen.empty()
        val back = VirtualScreen.decode(VirtualScreen.encode(empty))
        assertTrue(back.visiblePanes().isEmpty())
    }

    @Test
    fun `bolme basina yakalama butcesi ayridir`() {
        // [37099i] Ölçülen hata: global damga yüzünden hızlı dönen bir bölme,
        // iki durgun bölmeyi 250 ms boyunca aç bırakıyordu.
        var s = VirtualScreen.State()
        val t0 = 1_000L
        s = VirtualScreen.markCapturedAll(s, t0, VirtualScreen.needsCapture(s, t0))
        assertEquals(3, s.lastCaptureByPane.size)

        // 100 ms sonra browser'ın süresi dolmadı, hiçbiri istenmez.
        assertEquals(0, VirtualScreen.needsCapture(s, t0 + 100).size)

        // 300 ms sonra hepsi istenir.
        assertEquals(3, VirtualScreen.needsCapture(s, t0 + 300).size)
    }

    @Test
    fun `tek bolme yakalaninca digerleri ac kalmaz`() {
        var s = VirtualScreen.State()
        val t0 = 5_000L
        s = VirtualScreen.markCapturedAll(s, t0, listOf(VirtualScreen.Pane.BROWSER))
        // Browser yeni yakalandı; terminal ve not henüz hiç yakalanmadı → istenir.
        val wanted = VirtualScreen.needsCapture(s, t0 + 10)
        assertTrue("durgun bölmeler istenmeli", wanted.contains(VirtualScreen.Pane.TERMINAL))
        assertTrue(wanted.contains(VirtualScreen.Pane.NOTES))
        assertTrue("az önce yakalanan istenmemeli", !wanted.contains(VirtualScreen.Pane.BROWSER))
    }
}
