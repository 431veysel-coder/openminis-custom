package com.openminis.app.ui.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37127] Rolün ne verdiği.
 *
 * Neden test: en tehlikeli durum `UNKNOWN`. Tanınmayan bir rol **hata vermiyor**,
 * sessizce genel araç setini veriyor. Kullanıcı `araştırmacı` yazdığını sanıp
 * `shell_execute` beklerken elinde tam set buluyor. Bu ayrımın görünür olması
 * testle çivilenmeli.
 */
class RoleHintTest {

    @Test
    fun bos_rol_genel_sete_duser() {
        assertEquals(RoleHint.Kind.NO_ROLE, RoleHint.of("").kind)
        assertEquals(RoleHint.Kind.NO_ROLE, RoleHint.of("   ").kind)
    }

    @Test
    fun bilinen_rol_arac_listesini_getirir() {
        val r = RoleHint.of("researcher")
        assertEquals(RoleHint.Kind.PRESET, r.kind)
        assertTrue(r.tools.contains("browser_use"))
        // Arastirmaci kabuk ALMAZ — kullanicinin gordugu asil bilgi bu.
        assertFalse(r.tools.contains("shell_execute"))
    }

    @Test
    fun turkce_es_ad_da_taniniyor() {
        val tr = RoleHint.of("araştırmacı")
        assertEquals(RoleHint.Kind.PRESET, tr.kind)
        assertEquals(RoleHint.of("researcher").tools, tr.tools)
    }

    @Test
    fun taninmayan_rol_sessizce_gecmez() {
        val r = RoleHint.of("kahin")
        assertEquals(RoleHint.Kind.UNKNOWN, r.kind)
        assertTrue(r.tools.isEmpty())
    }

    @Test
    fun elle_secim_rolu_ezer_ve_bu_soylenir() {
        val r = RoleHint.of("researcher", listOf("shell_execute", "file_read"))
        assertEquals(RoleHint.Kind.EXPLICIT_TOOLS, r.kind)
        assertEquals(listOf("file_read", "shell_execute"), r.tools)
    }

    @Test
    fun uzun_liste_sayarak_kesilir() {
        val many = List(11) { "tool$it" }
        val s = RoleHint.toolSummary(many, keep = 4)
        assertTrue(s, s.endsWith("+7"))
        assertEquals("a, b", RoleHint.toolSummary(listOf("a", "b")))
        assertEquals("", RoleHint.toolSummary(emptyList()))
    }
}
