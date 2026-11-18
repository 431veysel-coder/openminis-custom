package com.openminis.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OZEL 37080] Politika ve defter testleri.
 *
 * Buradaki testler **asıl güvenlik iddiaları**: kalıcı izin sınırı, kapsam
 * terfisinin olmaması, reddin izni yenmesi, proje izninin proje dışına
 * taşmaması. Hepsi ürün kararı; kırılırlarsa özellik yanlış çalışıyor demektir.
 */
class ApprovalPolicyTest {

    private val session = ApprovalScope.Session("abc123")
    private val project = ApprovalScope.Project("folder-1")
    private val now = 1_700_000_000_000L

    private fun verdict(
        rule: String = "shell:rm",
        level: RiskLevel = RiskLevel.MEDIUM,
        reach: Reach = Reach.SANDBOX,
        recovery: Recovery = Recovery.RECOVERABLE,
        target: String = "/var/minis/shared/proje-a",
        segment: String = "rm x",
    ) = RiskVerdict(level, reach, recovery, rule, target, emptyList(), segment)

    @Test
    fun `kalici islem oturumdan uzun yasayan izin alamaz`() {
        // KARAR: ürünün tamamını taşıyan tek kural.
        val v = verdict(recovery = Recovery.PERMANENT, level = RiskLevel.HIGH)
        val scopes = ApprovalPolicy.offeredScopes(v, session, project)
        assertEquals(1, scopes.size)
        assertTrue(scopes.single() is ApprovalScope.Session)
    }

    @Test
    fun `geri donulebilir islem projede kalici izin alabilir`() {
        val v = verdict(recovery = Recovery.RECOVERABLE, reach = Reach.SANDBOX)
        val scopes = ApprovalPolicy.offeredScopes(v, session, project)
        assertTrue(scopes.contains(project))
    }

    @Test
    fun `cihaz geneli islem tum minise yayilmaz`() {
        val v = verdict(rule = "device:settings", reach = Reach.DEVICE, recovery = Recovery.UNDOABLE)
        val scopes = ApprovalPolicy.offeredScopes(v, session, project)
        assertFalse(scopes.contains(ApprovalScope.Global))
        assertTrue(scopes.contains(project)) // projede hatırlanabilir, cihazda değil
    }

    @Test
    fun `ag kapsamli islem projede hatirlanmaz`() {
        val v = verdict(rule = "net:upload", reach = Reach.NETWORK, recovery = Recovery.PERMANENT)
        val scopes = ApprovalPolicy.offeredScopes(v, session, project)
        assertEquals(1, scopes.size)
        assertTrue(scopes.single() is ApprovalScope.Session)
    }

    @Test
    fun `dusuk riskli is sessiz gecer`() {
        val book = GrantBook(session, project)
        val d = ApprovalPolicy.decide(verdict(level = RiskLevel.LOW), book, now)
        assertTrue(d is GateDecision.Silent)
    }

    @Test
    fun `verilen izin ikinci kez sorulmaz ve sayilir`() {
        val v = verdict()
        val book = GrantBook(session, project)
        book.put(
            Grant(
                ruleId = v.ruleId, targetPrefix = v.targetPrefix, scope = project,
                decision = Decision.ALLOW, grantedAtMs = now, expiresAtMs = null,
            ),
        )
        val d = ApprovalPolicy.decide(v, book, now)
        assertTrue(d is GateDecision.Silent)
        assertEquals(true, (d as GateDecision.Silent).viaGrant)
    }

    @Test
    fun `reddin gerekcesi ajana aynen doner`() {
        val v = verdict()
        val book = GrantBook(session, project)
        book.put(
            Grant(
                ruleId = v.ruleId, targetPrefix = v.targetPrefix, scope = session,
                decision = Decision.DENY, grantedAtMs = now, expiresAtMs = null,
                note = "kullanıcı bu dizinde silme istemiyor",
            ),
        )
        val d = ApprovalPolicy.decide(v, book, now)
        assertTrue(d is GateDecision.Deny)
        val msg = d.forAgent()
        assertTrue(msg.contains("kullanıcı bu dizinde silme istemiyor"))
        assertTrue(msg.contains("tekrar denemeyin"))
    }

    @Test
    fun `dar kapsamdaki ret genis kapsamdaki izni yener`() {
        // KARAR: kullanıcı projede izin verip sohbette reddettiyse, ret kazanır.
        val v = verdict()
        val book = GrantBook(session, project)
        book.put(
            Grant(v.ruleId, v.targetPrefix, project, Decision.ALLOW, now, null),
        )
        book.put(
            Grant(v.ruleId, v.targetPrefix, session, Decision.DENY, now, null, note = "hayır"),
        )
        assertTrue(ApprovalPolicy.decide(v, book, now) is GateDecision.Deny)
    }

    @Test
    fun `proje izni proje disindaki yola uzanmaz`() {
        val book = GrantBook(session, project)
        book.put(
            Grant(
                ruleId = "shell:rm", targetPrefix = "/var/minis/shared/proje-a",
                scope = project, decision = Decision.ALLOW, grantedAtMs = now, expiresAtMs = null,
            ),
        )
        // Aynı kural, komşu dizin → izin yok, sorulur.
        val other = verdict(target = "/var/minis/shared/proje-b")
        assertNull(book.lookup(other.ruleId, other.targetPrefix, now))
        assertTrue(ApprovalPolicy.decide(other, book, now) is GateDecision.Ask)
    }

    @Test
    fun `onek eslesmesi dizin sinirinda olur`() {
        val g = Grant("shell:rm", "/var/minis/shared/p", session, Decision.ALLOW, now, null)
        assertTrue(g.covers("shell:rm", "/var/minis/shared/p/a"))
        assertFalse(g.covers("shell:rm", "/var/minis/shared/pq/a")) // "p" ile "pq" karışmaz
    }

    @Test
    fun `sureli izin suresi dolunca yeniden sorar`() {
        val v = verdict(recovery = Recovery.PERMANENT)
        val book = GrantBook(session, project)
        book.put(
            Grant(v.ruleId, v.targetPrefix, session, Decision.ALLOW, now, expiresAtMs = now + 1000),
        )
        assertTrue(ApprovalPolicy.decide(v, book, now + 2000) is GateDecision.Ask)
    }

    @Test
    fun `kullanim tavani dolunca yeniden sorar`() {
        // KARAR: sessiz çoğalmaya karşı. Ajan döngüye girerse 21. kez sorulur.
        val v = verdict(recovery = Recovery.PERMANENT, level = RiskLevel.HIGH)
        val g = Grant(v.ruleId, v.targetPrefix, session, Decision.ALLOW, now, null, maxUses = 20)
        val book = GrantBook(session, project)
        book.put(g)
        repeat(20) { book.consume(g, now) }
        assertEquals(0, book.inScope(session).size)
        assertTrue(ApprovalPolicy.decide(v, book, now) is GateDecision.Ask)
    }

    @Test
    fun `ayni kural yeni kararla ezilir`() {
        val book = GrantBook(session, project)
        book.put(Grant("shell:rm", "/x", session, Decision.DENY, now, null))
        book.put(Grant("shell:rm", "/x", session, Decision.ALLOW, now, null))
        assertEquals(1, book.inScope(session).size)
        assertEquals(Decision.ALLOW, book.inScope(session).single().decision)
    }

    @Test
    fun `kapsam anahtari proje klasorune baglidir`() {
        assertEquals("project:folder-1", project.key)
        assertEquals(project, ApprovalScope.parse("project:folder-1"))
        assertEquals("session:abc123", session.key)
        assertNotNull(ApprovalScope.parse("global"))
        assertNull(ApprovalScope.parse("bilinmeyen"))
    }
}
