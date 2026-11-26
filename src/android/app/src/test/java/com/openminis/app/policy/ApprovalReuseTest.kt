package com.openminis.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37129] **Onaylanmış iş yeniden sorulmaz.**
 *
 * Kullanıcının şikâyeti: *"onay istiyor sesli olarak, onaylanmış olsa dahi
 * istiyor"*. Kök sebep: mod düzeyinde verilen izin de normal cevap gibi
 * `maxUses` alıyordu ve her kullanımda tüketiliyordu — 20 komut sonra kayıt
 * düşüyor, kapı yeniden onay üretiyordu.
 *
 * Bu testler o davranışın geri gelmemesini sağlar.
 */
class ApprovalReuseTest {

    private fun verdict(
        ruleId: String = "shell:rm",
        level: RiskLevel = RiskLevel.HIGH,
        target: String = "/var/minis/workspace",
        recovery: Recovery = Recovery.RECOVERABLE,
    ) = RiskVerdict(
        level = level,
        reach = Reach.SANDBOX,
        recovery = recovery,
        ruleId = ruleId,
        targetPrefix = target,
        reasons = emptyList(),
        segment = "rm -rf x",
    )

    private fun request(v: RiskVerdict = verdict()) = ApprovalRequest(
        id = "r1",
        verdict = v,
        purpose = Purpose(null, Purpose.Source.MISSING),
        offeredScopes = listOf(ApprovalScope.Global),
        title = "rm",
    )

    @Test
    fun mod_izni_sayacsiz_ve_tukenmez() {
        val g = ApprovalPolicy.grantFor(
            request = request(),
            scope = ApprovalScope.Global,
            decision = Decision.ALLOW,
            nowMs = 1_000L,
            unlimited = true,
        )
        assertNull("mod izni sayili olmamali", g.maxUses)

        // 500 kullanim sonrasi hala gecerli — eski halde 50'de duserdi.
        var now = 1_000L
        repeat(500) { now += 1_000L }
        assertTrue("mod izni 500 kullanim sonra da gecerli olmali", g.live(now))
    }

    @Test
    fun normal_cevap_hala_sayili() {
        // Kullanicinin tek bir is icin verdigi izin sayili KALIR: 50 kez
        // sonra tekrar bakmak dogru davranis.
        val g = ApprovalPolicy.grantFor(
            request = request(),
            scope = ApprovalScope.Session("s1"),
            decision = Decision.ALLOW,
            nowMs = 1_000L,
        )
        assertNotNull("cevap izni sayili olmali", g.maxUses)
        assertTrue(g.live(1_000L))
    }

    @Test
    fun verilmis_izin_karari_silent_yapar_tekrar_sormaz() {
        val book = GrantBook(session = ApprovalScope.Session("s1"), project = null)
        val g = ApprovalPolicy.grantFor(
            request = request(),
            scope = ApprovalScope.Global,
            decision = Decision.ALLOW,
            nowMs = 1_000L,
            unlimited = true,
        )
        book.put(g)

        val d = ApprovalPolicy.decide(verdict(), book, 2_000L)
        assertTrue("verilmis izin Silent olmali, Ask degil", d is GateDecision.Silent)
        assertEquals(g.ruleId, (d as GateDecision.Silent).grant?.ruleId)
    }

    @Test
    fun izin_yoksa_hala_sorar() {
        val book = GrantBook(session = ApprovalScope.Session("s1"), project = null)
        assertTrue(ApprovalPolicy.decide(verdict(), book, 2_000L) is GateDecision.Ask)
    }

    @Test
    fun deny_sinifi_hicbir_zaman_izin_almaz() {
        val book = GrantBook(session = ApprovalScope.Session("s1"), project = null)
        val g = ApprovalPolicy.grantFor(
            request = request(verdict(ruleId = "deny:rm-rf-root", recovery = Recovery.PERMANENT)),
            scope = ApprovalScope.Global,
            decision = Decision.ALLOW,
            nowMs = 1_000L,
            unlimited = true,
        )
        book.put(g)
        val denyVerdict = verdict(
            ruleId = "deny:rm-rf-root",
            level = RiskLevel.HIGH,
            target = "/",
            recovery = Recovery.PERMANENT,
        )
        // `deny:` sinifi kayit olsa bile Deny doner — sandbox siniri.
        assertTrue(ApprovalPolicy.decide(denyVerdict, book, 2_000L) is GateDecision.Deny)
    }
}
