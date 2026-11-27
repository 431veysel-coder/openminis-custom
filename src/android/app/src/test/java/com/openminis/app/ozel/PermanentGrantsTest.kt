package com.openminis.app.ozel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [H-017] Kalıcı izin kararı — saf kısım.
 *
 * Android'e bağlı olan `PermanentGrants` (dosya + SharedPreferences) JVM'de
 * çalışmıyor; asıl tehlike **kural seçimi**: hangi kural yazılır, hangisi
 * asla yazılmaz. Bu dosya o kararı sınıyor.
 */
class PermanentGrantsTest {

    @Test
    fun `deny rules can never be written`() {
        // Sınıflandırıcı onları Deny döndürüyor ve `grantFor` hiç çağrılmıyor;
        // bu testin işi o kapıyı **yazılı** tutmak: `RiskRules.DENY` listesi
        // `grantableRules()` sonucunda görünmemeli.
        val grantable = PermanentGrants.grantableRules().map { it.id }.toSet()
        val denyIds = com.openminis.app.policy.RiskRules.DENY.map { it.id }
        assertTrue(denyIds.isNotEmpty())
        denyIds.forEach { id ->
            assertFalse("deny kuralı yazılabilir kümede: $id", id in grantable)
        }
    }

    @Test
    fun `low risk rules are not written`() {
        // ALLOW seviyesindeki (soru gerektirmeyen) kurallar için kayıt yazmak
        // dosyayı şişirir ve "kaç kural açık" sayısını yanlış gösterir.
        val grantable = PermanentGrants.grantableRules().map { it.id }.toSet()
        val low = com.openminis.app.policy.RiskRules.ALL
            .filter { it.level < com.openminis.app.policy.ApprovalPolicy.ASK_FROM }
            .map { it.id }
        low.forEach { id ->
            assertFalse("düşük riskli kural gereksiz yazılıyor: $id", id in grantable)
        }
    }

    @Test
    fun `a grant written unlimited has no expiry and no counter`() {
        val verdict = com.openminis.app.policy.RiskVerdict(
            level = com.openminis.app.policy.RiskLevel.HIGH,
            reach = com.openminis.app.policy.Reach.SANDBOX,
            recovery = com.openminis.app.policy.Recovery.UNDOABLE,
            ruleId = "shell:rm",
            targetPrefix = "",
            reasons = emptyList(),
            segment = "rm x",
        )
        val request = com.openminis.app.policy.ApprovalRequest.of(
            verdict,
            com.openminis.app.policy.Purpose(null, com.openminis.app.policy.Purpose.Source.MISSING),
            scopes = emptyList(),
        )
        val permanent = com.openminis.app.policy.ApprovalPolicy.grantFor(
            request = request,
            scope = com.openminis.app.policy.ApprovalScope.Global,
            decision = com.openminis.app.policy.Decision.ALLOW,
            nowMs = 1_000L,
            unlimited = true,
        )
        assertNull("kalıcı izin süreli olmamalı", permanent.expiresAtMs)
        assertNull("kalıcı izin sayaçlı olmamalı", permanent.maxUses)
        assertTrue(permanent.live(nowMs = 1_000L + 365L * 24 * 3600 * 1000))
    }

    @Test
    fun `an answer to a question keeps its expiry`() {
        val verdict = com.openminis.app.policy.RiskVerdict(
            level = com.openminis.app.policy.RiskLevel.HIGH,
            reach = com.openminis.app.policy.Reach.SANDBOX,
            recovery = com.openminis.app.policy.Recovery.UNDOABLE,
            ruleId = "shell:rm",
            targetPrefix = "",
            reasons = emptyList(),
            segment = "rm x",
        )
        val request = com.openminis.app.policy.ApprovalRequest.of(
            verdict,
            com.openminis.app.policy.Purpose(null, com.openminis.app.policy.Purpose.Source.MISSING),
            scopes = emptyList(),
        )
        val once = com.openminis.app.policy.ApprovalPolicy.grantFor(
            request = request,
            scope = com.openminis.app.policy.ApprovalScope.Global,
            decision = com.openminis.app.policy.Decision.ALLOW,
            nowMs = 1_000L,
            unlimited = false,
        )
        // `ApprovalPolicy.ttlMs`: Global + RECOVERABLE = 90 gün, diğerleri
        // (UNDOABLE/PERMANENT) = 180 gün. Bu kayıt UNDOABLE, yani 180.
        // Sınanan şey **sürenin var olması**: `unlimited` yalnız mod kararında
        // süreyi kaldırır, soruya verilen cevapta kaldırmaz.
        assertEquals("soru cevabı ömrünü korumalı", 1_000L + 180L * 24 * 3600 * 1000, once.expiresAtMs)
    }
}
