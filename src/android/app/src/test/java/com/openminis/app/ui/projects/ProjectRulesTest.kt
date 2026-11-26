package com.openminis.app.ui.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37126] Proje işlemlerinin kuralları.
 *
 * Neden test: ekran artık oluşturuyor, yeniden adlandırıyor ve **siliyor**.
 * Silme cümlesi yanlış kurulursa kullanıcı ya sohbetlerinin de gittiğini sanır
 * ya da tersini sanıp grupsuz kaldığını fark etmez.
 */
class ProjectRulesTest {

    @Test
    fun bos_ad_gecersiz() {
        assertFalse(ProjectRules.isValidName(""))
        assertFalse(ProjectRules.isValidName("   "))
        assertNull(ProjectRules.normalise("  "))
    }

    @Test
    fun ad_kirpilir_ve_sinir_uygulanir() {
        assertEquals("Proje", ProjectRules.normalise("  Proje  "))
        assertTrue(ProjectRules.isValidName("a".repeat(ProjectRules.NAME_MAX)))
        assertFalse(ProjectRules.isValidName("a".repeat(ProjectRules.NAME_MAX + 1)))
    }

    @Test
    fun cozme_cumlesi_sohbet_sayisina_gore_sekil_degistirir() {
        assertEquals(ProjectRules.DissolveShape.EMPTY, ProjectRules.dissolveShape(0))
        assertEquals(ProjectRules.DissolveShape.EMPTY, ProjectRules.dissolveShape(-1))
        assertEquals(ProjectRules.DissolveShape.ONE, ProjectRules.dissolveShape(1))
        assertEquals(ProjectRules.DissolveShape.MANY, ProjectRules.dissolveShape(2))
        assertEquals(ProjectRules.DissolveShape.MANY, ProjectRules.dissolveShape(99))
    }

    @Test
    fun adsiz_proje_ayirt_edilir() {
        assertTrue(ProjectRules.isNameless(""))
        assertTrue(ProjectRules.isNameless("  \t "))
        assertFalse(ProjectRules.isNameless("P"))
    }
}
