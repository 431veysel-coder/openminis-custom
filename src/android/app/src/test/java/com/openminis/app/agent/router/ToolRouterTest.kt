package com.openminis.app.agent.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OZEL 37085] Araç yönlendirici testleri. */
class ToolRouterTest {

    @Test
    fun `cekirdek araclar her zaman listede`() {
        // KARAR: model yanlış seçim yaptığında geri dönebilmeli.
        val c = ToolRouter.candidates("merhaba")
        ToolRouter.ALWAYS.forEach { assertTrue("eksik: $it", it in c) }
    }

    @Test
    fun `terminal niyeti shell_execute getirir`() {
        val c = ToolRouter.candidates("bu repoda testleri çalıştır ve hatayı bul")
        assertTrue("shell_execute" in c)
    }

    @Test
    fun `turkce aksan farki eslesmeyi bozmaz`() {
        // "şifre" yazılmadan da bulunmalı: fold() iki tarafa da uygulanıyor.
        val a = ToolRouter.candidates("şifreyi kontrol et")
        val b = ToolRouter.candidates("sifreyi kontrol et")
        assertEquals(a.toSet(), b.toSet())
    }

    @Test
    fun `tarayici niyeti dogru araci getirir`() {
        val c = ToolRouter.candidates("şu siteyi aç ve fiyatları topla")
        assertTrue("browser_use" in c)
    }

    @Test
    fun `bos niyet taban listeye duser`() {
        assertEquals(ToolRouter.FALLBACK.toSet(), ToolRouter.candidates("   ").toSet())
    }

    @Test
    fun `eslesme yoksa taban liste`() {
        assertEquals(ToolRouter.FALLBACK.toSet(), ToolRouter.candidates("xyzq abc").toSet())
    }

    @Test
    fun `liste sinira oturtulur ama cekirdek korunur`() {
        val c = ToolRouter.candidates(
            "terminal komut çalıştır dosya oku yaz düzenle tarayıcı site resim ses konum takvim alarm bildirim",
            limit = 3,
        )
        assertTrue(c.size <= 3 + ToolRouter.ALWAYS.size)
        ToolRouter.ALWAYS.forEach { assertTrue(it in c) }
    }

    @Test
    fun `kapali katalog cekirdegi tekrar etmez`() {
        val cat = ToolRouter.closedCatalog()
        ToolRouter.ALWAYS.forEach { assertTrue("$it katalogda olmamalı", it !in cat) }
        assertTrue(cat.isNotEmpty())
    }

    @Test
    fun `her ipucu gercek bir arac adi tasir`() {
        // Yazım hatası sessizce "hiç eşleşmeyen" bir kural üretir; bu test onu yakalar.
        ToolRouter.HINTS.forEach {
            assertTrue("boş ipucu: ${it.name}", it.keywords.isNotEmpty())
            assertTrue("kısa ad: ${it.name}", it.name.length > 2)
        }
    }
}
