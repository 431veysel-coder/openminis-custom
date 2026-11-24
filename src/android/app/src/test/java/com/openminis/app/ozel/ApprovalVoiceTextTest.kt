package com.openminis.app.ozel

import com.openminis.app.policy.ApprovalRequest
import com.openminis.app.policy.ApprovalScope
import com.openminis.app.policy.Purpose
import com.openminis.app.policy.Reach
import com.openminis.app.policy.Recovery
import com.openminis.app.policy.RiskLevel
import com.openminis.app.policy.RiskReason
import com.openminis.app.policy.RiskVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37116] Sesli onay metni — ne söyleneceğinin ve ne söylenmeyeceğinin testi.
 *
 * Neden test: bu metin **duyulan** tek şey. Yanlış kurulursa iki zarar var:
 * (a) cümle uzar, duyuru 6 saniye sürer ve kullanıcı ikinci yarısını kaçırır;
 * (b) güvenlik bilgisi (geri gelmez / telefon geneli) düşer ve kullanıcı
 * sesli uyarıya dayanarak yanlış karar verir. İkisi de sessiz hatadır.
 */
class ApprovalVoiceTextTest {

    private fun request(
        ruleId: String = "shell:rm-recursive",
        segment: String = "rm -rf /var/minis/shared/proje-a",
        recovery: Recovery = Recovery.PERMANENT,
        reach: Reach = Reach.SESSION,
        title: String = "Dizin ağacı silinecek",
    ): ApprovalRequest {
        val verdict = RiskVerdict(
            level = RiskLevel.HIGH,
            reach = reach,
            recovery = recovery,
            ruleId = ruleId,
            targetPrefix = "",
            reasons = listOf(RiskReason(RiskReason.Kind.EFFECT, "dizin ağacını siler")),
            segment = segment,
        )
        return ApprovalRequest(
            id = "test#1",
            verdict = verdict,
            purpose = Purpose(null, Purpose.Source.MISSING),
            offeredScopes = listOf(ApprovalScope.Session("s")),
            title = title,
        )
    }

    @Test
    fun ilk_duyuru_bekliyorum_ile_baslar_tekrar_halâ_ile() {
        val first = ApprovalVoiceText.line(request(), repeat = false)
        val again = ApprovalVoiceText.line(request(), repeat = true)
        assertTrue(first, first.startsWith("Onay bekliyorum."))
        assertTrue(again, again.startsWith("Hâlâ onay bekliyorum."))
        // Aynı cümleyi aynen tekrarlamak "yeni istek geldi" izlenimi verir.
        assertFalse(first == again)
    }

    @Test
    fun geri_gelmez_soylenir_geri_alinabilir_soylenmez() {
        val permanent = ApprovalVoiceText.line(request(recovery = Recovery.PERMANENT))
        assertTrue(permanent, permanent.contains("Geri gelmez"))

        val recoverable = ApprovalVoiceText.line(request(recovery = Recovery.RECOVERABLE))
        assertTrue(recoverable, recoverable.contains("Kendiliğinden geri gelmez"))

        // Geri alınabilir işte bu cümle 1.5 saniye yer kaplar ve bilgi vermez.
        assertNull(ApprovalVoiceText.recoveryPhrase(request(recovery = Recovery.UNDOABLE)))
    }

    @Test
    fun cihaz_ve_ag_erisimi_soylenir_sandbox_sessiz() {
        assertNull(ApprovalVoiceText.reachPhrase(request(reach = Reach.SANDBOX)))
        assertNull(ApprovalVoiceText.reachPhrase(request(reach = Reach.SESSION)))
        assertEquals(
            "Telefon genelini etkiler",
            ApprovalVoiceText.reachPhrase(request(reach = Reach.DEVICE)),
        )
        assertTrue(
            ApprovalVoiceText.line(request(reach = Reach.NETWORK))
                .contains("Bu cihazın dışını etkiler"),
        )
    }

    @Test
    fun komut_yetmis_karakterde_kesilir_ve_islem_oneki_tasir() {
        val long = "x".repeat(200)
        val op = ApprovalVoiceText.operation(request(segment = long))
        assertTrue(op != null)
        assertTrue(op!!.startsWith("İşlem: "))
        // 70 sınırı: önek + 70 karakter (son karakter "…" olabilir).
        assertTrue("çok uzun: ${op.length}", op.length <= "İşlem: ".length + 70)
        assertTrue(op.endsWith("…"))
    }

    @Test
    fun bos_komutta_islem_cumlesi_uretilmez() {
        // Uydurulmuş bir cümle, sessizlikten kötüdür.
        assertNull(ApprovalVoiceText.operation(request(segment = "   ")))
        val line = ApprovalVoiceText.line(request(segment = "  "))
        assertFalse(line.contains("İşlem:"))
    }

    @Test
    fun kuyrukta_birden_cok_kart_varsa_sayi_soylenir() {
        val one = ApprovalVoiceText.line(request(), pending = 1)
        val three = ApprovalVoiceText.line(request(), pending = 3)
        assertFalse(one.contains("istek bekliyor"))
        assertTrue(three, three.contains("3 istek bekliyor"))
    }

    @Test
    fun komut_kapaliyken_islem_okunmaz_geri_donus_yine_okunur() {
        val line = ApprovalVoiceText.line(request(), speakOperation = false)
        assertFalse(line.contains("İşlem:"))
        // Güvenlik satırı ayardan bağımsız: kapatılabilir olmamalı.
        assertTrue(line, line.contains("Geri gelmez"))
    }

    @Test
    fun bildirim_govdesi_ses_cumlesinin_ayni_halidir() {
        // İki yerde iki farklı metin üretmek "ekran başka, ses başka" derdi.
        val r = request()
        assertEquals(
            ApprovalVoiceText.line(r, pending = 2, repeat = false, speakOperation = true),
            ApprovalVoiceText.notificationBody(r, pending = 2),
        )
    }

    @Test
    fun ayar_sinirlari_zorlanir_ve_titresim_deseni_sabittir() {
        val low = VoiceSettings(repeatSeconds = 1)
        val high = VoiceSettings(repeatSeconds = 9999)
        assertEquals(VoiceSettings.MIN_REPEAT_SECONDS * 1000L, low.repeatMs)
        assertEquals(VoiceSettings.MAX_REPEAT_SECONDS * 1000L, high.repeatMs)
        assertEquals(4, ApprovalVoiceText.vibrationPattern().size)
    }

    @Test
    fun ses_modu_bilinmeyen_degerde_acik_kalir() {
        // Kullanıcı bu ayarı zaten açtıysa bozuk değer onu susturmamalı.
        assertEquals(VoiceMode.REPEATING, VoiceMode.parse(null))
        assertEquals(VoiceMode.REPEATING, VoiceMode.parse("bozuk"))
        assertEquals(VoiceMode.OFF, VoiceMode.parse("off"))
        assertEquals(VoiceMode.ONCE, VoiceMode.parse("once"))
    }
}
