package com.openminis.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37116] Onay modu — "kart ne zaman çıksın" kararının çekirdeği.
 *
 * Neden test: bu enum **güvenlik sınırını** değiştiriyor. `ALLOW_ALL` bir
 * yazım hatası yüzünden `deny:` sınıfını açarsa (`rm -rf /` her modda
 * çalışıyorsa) onay sistemi tamamen süs olur. Testler üç şeyi çiviliyor:
 *
 *  1. `deny:` hiçbir modda otomatik izin almaz.
 *  2. `SETTINGS_ONLY` yalnız **verilebilir** kuralları (katalog kuralı:
 *     reach ≤ SANDBOX ve recovery ≠ PERMANENT) sessizleştirir.
 *  3. `ASK_EACH` hiçbir kuralı sessizleştirmez — eski davranış korunur.
 */
class ApprovalModeTest {

    @Test
    fun bilinmeyen_deger_varsayilana_duser_ve_varsayilan_settings_only() {
        // Bozuk tercih kullanıcıyı kart yağmuruna döndürmemeli.
        assertEquals(ApprovalMode.SETTINGS_ONLY, ApprovalMode.parse(null))
        assertEquals(ApprovalMode.SETTINGS_ONLY, ApprovalMode.parse(""))
        assertEquals(ApprovalMode.SETTINGS_ONLY, ApprovalMode.parse("  "))
        assertEquals(ApprovalMode.SETTINGS_ONLY, ApprovalMode.parse("bozuk"))
    }

    @Test
    fun kayitli_deger_aynen_okunur() {
        assertEquals(ApprovalMode.ASK_EACH, ApprovalMode.parse("ask_each"))
        assertEquals(ApprovalMode.ALLOW_ALL, ApprovalMode.parse(" allow_all "))
        assertEquals(ApprovalMode.SETTINGS_ONLY, ApprovalMode.parse("settings_only"))
    }

    @Test
    fun ask_each_hicbir_kurali_sessizlestirmez() {
        listOf("shell:rm", "pkg:apk", "net:ssh", "plan:long-run").forEach { id ->
            val rule = RiskRules.byId(id)
            assertTrue("kural bulunamadı: $id", rule != null)
            assertFalse(id, ApprovalMode.ASK_EACH.autoGrants(rule!!))
        }
    }

    @Test
    fun settings_only_yalniz_verilebilir_kurallari_sessizlestirir() {
        val grantable = RiskRules.byId("shell:rm")
        assertTrue(grantable != null)
        assertTrue(
            "sandbox içi + geri alınabilir silme uygulama geneli izin almalı",
            ApprovalMode.SETTINGS_ONLY.autoGrants(grantable!!),
        )

        // Sandbox dışına taşan bir kural: katalog da bunu "verilemez" sayar.
        val device = RiskRules.byId("device:sudo")
        assertTrue(device != null)
        assertFalse(
            "cihaz geneli kural SETTINGS_ONLY'de sessizleşmemeli",
            ApprovalMode.SETTINGS_ONLY.autoGrants(device!!),
        )
        assertFalse(GrantCatalog.item(device).grantable)
    }

    @Test
    fun allow_all_geri_alinamaz_kurallari_da_sessizlestirir() {
        // Geri alınamaz silme: SETTINGS_ONLY'de kart, ALLOW_ALL'de sessiz.
        val permanent = RiskRules.byId("shell:rm-recursive")
        assertTrue(permanent != null)
        assertFalse(ApprovalMode.SETTINGS_ONLY.autoGrants(permanent!!))
        assertTrue(ApprovalMode.ALLOW_ALL.autoGrants(permanent))
    }

    @Test
    fun deny_sinifi_hicbir_modda_otomatik_izin_almaz() {
        assertTrue("deny listesi boş olmamalı", RiskRules.DENY.isNotEmpty())
        ApprovalMode.entries.forEach { mode ->
            RiskRules.DENY.forEach { rule ->
                assertFalse(
                    "${mode.key} modu ${rule.id} kuralını açmamalı — bu sandbox sınırı, tercih değil",
                    mode.autoGrants(rule),
                )
            }
        }
    }

    @Test
    fun gosterim_sirasi_en_az_kesintiden_baslar() {
        assertEquals(ApprovalMode.SETTINGS_ONLY, ApprovalMode.displayOrder.first())
        assertEquals(ApprovalMode.ASK_EACH, ApprovalMode.displayOrder.last())
        assertEquals(ApprovalMode.entries.size, ApprovalMode.displayOrder.size)
    }
}
