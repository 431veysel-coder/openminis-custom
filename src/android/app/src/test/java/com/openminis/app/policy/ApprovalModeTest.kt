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
    fun bilinmeyen_deger_varsayilana_duser_ve_varsayilan_allow_all() {
        // Kullanıcı "tüm izinler sende" dedi: bozuk bir tercih onu yeniden
        // kart yağmuruna döndürmemeli. Varsayılan ALLOW_ALL.
        assertEquals(ApprovalMode.ALLOW_ALL, ApprovalMode.parse(null))
        assertEquals(ApprovalMode.ALLOW_ALL, ApprovalMode.parse(""))
        assertEquals(ApprovalMode.ALLOW_ALL, ApprovalMode.parse("  "))
        assertEquals(ApprovalMode.ALLOW_ALL, ApprovalMode.parse("bozuk"))
    }

    @Test
    fun kayitli_deger_aynen_okunur() {
        assertEquals(ApprovalMode.ASK_EACH, ApprovalMode.parse("ask_each"))
        assertEquals(ApprovalMode.ALLOW_ALL, ApprovalMode.parse(" allow_all "))
        assertEquals(ApprovalMode.SETTINGS_ONLY, ApprovalMode.parse("settings_only"))
    }

    @Test
    fun ask_each_hicbir_kurali_sessizlestirmez() {
        listOf("shell:rm", "git:reset-hard", "net:ssh", "plan:long-run").forEach { id ->
            val rule = RiskRules.byId(id)
            assertTrue("kural bulunamadı: $id", rule != null)
            assertFalse(id, ApprovalMode.ASK_EACH.autoGrants(rule!!))
        }
    }

    @Test
    fun settings_only_yalniz_verilebilir_kurallari_sessizlestirir() {
        // MEDIUM + sandbox içi + kurtarılabilir: katalog da "verilebilir" der.
        val grantable = RiskRules.byId("git:reset-hard")
        assertTrue(grantable != null)
        assertTrue(
            "sandbox içi + kurtarılabilir iş uygulama geneli izin almalı",
            ApprovalMode.SETTINGS_ONLY.autoGrants(grantable!!),
        )
        assertTrue(GrantCatalog.item(grantable).grantable)

        // Silme geri alınamaz olduğu için SETTINGS_ONLY'de kart çıkar.
        val rm = RiskRules.byId("shell:rm")
        assertTrue(rm != null)
        assertFalse("geri alınamaz silme kartla sorulmalı", ApprovalMode.SETTINGS_ONLY.autoGrants(rm!!))

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
        val permanent = RiskRules.byId("shell:rm")
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
        assertEquals(ApprovalMode.ALLOW_ALL, ApprovalMode.displayOrder.first())
        assertEquals(ApprovalMode.ASK_EACH, ApprovalMode.displayOrder.last())
        assertEquals(ApprovalMode.entries.size, ApprovalMode.displayOrder.size)
    }

    @Test
    fun allow_all_deny_disarinda_her_seyi_sessizlestirir_ama_duyurur() {
        // Sessiz geçen her kural, "duyurulacak" sınıflarda olmalı: kart kalktı
        // diye haber de kalkmamalı (geri gelmez + cihaz/ağ/hesap).
        val permanent = RiskRules.byId("shell:rm-recursive")
        assertTrue(permanent != null)
        assertTrue(ApprovalMode.ALLOW_ALL.autoGrants(permanent!!))
        assertTrue(
            "geri alınamaz silme duyurulmali (kart degil)",
            ApprovalMode.ALLOW_ALL.notifyOnly(permanent),
        )

        val device = RiskRules.byId("device:sudo")
        assertTrue(device != null)
        assertTrue(ApprovalMode.ALLOW_ALL.notifyOnly(device!!))

        // Sandbox içi + geri alınabilir iş sessiz geçmeli: her `apk add` için
        // konuşmak, sesi gürültüye çevirir ve gerçek uyarıyı duyulmaz yapar.
        val quiet = RiskRules.byId("pkg:install")
        assertTrue(quiet != null)
        assertFalse(ApprovalMode.ALLOW_ALL.notifyOnly(quiet!!))
    }

    @Test
    fun deny_sinifi_duyurulmaz_cunku_calismaz() {
        // Reddedilen bir iş için "yapıyorum" demek yalan olurdu; `Deny` yolu
        // zaten `notifyOnly`a hiç gelmiyor.
        val deny = RiskRules.DENY.first()
        assertFalse(ApprovalMode.ALLOW_ALL.autoGrants(deny))
    }
}
