package com.openminis.app.ui.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37125 · §8.4] Ekran okuyucu etiketleri.
 *
 * ## Neden test
 * Matris hücresi görsel olarak tek karakter (`✓`). TalkBack onu "onay işareti"
 * diye okur; hangi üye, hangi iddia, hangi duruş belirsiz kalır — tablonun tüm
 * bilgisi erişilemez olur. Bu cihazda emülatör olmadığı için etiketin doğru
 * kurulduğu ancak burada ölçülebilir.
 */
class TeamA11yTest {

    private fun label(stance: ClaimMatrixLayout.Stance?) = TeamA11y.cellLabel(
        member = "Ayşe",
        claimId = "C3",
        stance = stance,
        supportWord = "destekliyor",
        opposeWord = "karşı çıkıyor",
        uncertainWord = "kararsız",
        emptyWord = "pozisyon yok",
    )

    @Test
    fun hucre_etiketi_uye_iddia_ve_durustu_birlikte_soyler() {
        val s = label(ClaimMatrixLayout.Stance.SUPPORT)
        assertTrue(s, s.contains("Ayşe"))
        assertTrue(s, s.contains("C3"))
        assertTrue(s, s.contains("destekliyor"))
    }

    @Test
    fun her_durus_kendi_kelimesini_alir() {
        assertTrue(label(ClaimMatrixLayout.Stance.OPPOSE).contains("karşı çıkıyor"))
        assertTrue(label(ClaimMatrixLayout.Stance.UNCERTAIN).contains("kararsız"))
        // Oy vermemiş üye: hücre boş, "destekliyor" DENMEZ.
        val none = label(null)
        assertTrue(none, none.contains("pozisyon yok"))
        assertFalse(none, none.contains("destekliyor"))
    }

    @Test
    fun etiket_hic_bir_zaman_bos_degil() {
        listOf(
            ClaimMatrixLayout.Stance.SUPPORT,
            ClaimMatrixLayout.Stance.OPPOSE,
            ClaimMatrixLayout.Stance.UNCERTAIN,
            null,
        ).forEach { assertTrue(label(it).isNotBlank()) }
    }

    @Test
    fun secili_sekme_soylenir_secilmeyen_susar() {
        assertEquals("Görev, seçili", TeamA11y.tabLabel("Görev", true, "seçili"))
        assertEquals("Görev", TeamA11y.tabLabel("Görev", false, "seçili"))
    }

    @Test
    fun arac_rozeti_bos_sa_uydurma_yapmaz() {
        val withTool = TeamA11y.toolBadge("browser_use", "Mehmet", "çalışıyor")
        assertTrue(withTool.contains("browser_use"))
        val without = TeamA11y.toolBadge(null, "Mehmet", "çalışıyor")
        assertTrue(without.contains("Mehmet"))
        assertTrue(without.contains("çalışıyor"))
        assertFalse(without.contains("null"))
    }

    @Test
    fun bos_durum_eylemi_varsa_tasinir_yoksa_uydurulmaz() {
        val withAction = TeamA11y.emptyState("Henüz koşu yok", "Ekip seç")
        assertEquals("Ekip seç", withAction.actionLabel)
        assertNull(TeamA11y.emptyState("Henüz koşu yok", "   ").actionLabel)
        assertNull(TeamA11y.emptyState("Henüz koşu yok", null).actionLabel)
    }
}
