package com.openminis.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OZEL 37080] Kuyruk testleri — toplu onay sınırının çivisi.
 *
 * Kullanıcının şartı: *"olabildiğince de toplu onay istemeye dikkat edelim"*.
 * Buradaki iki test o şartın iki yüzü: aynı soru tekrarlanmaz, farklı sorular
 * birleşmez.
 */
class ApprovalQueueTest {

    private val session = ApprovalScope.Session("s1")

    private fun req(
        rule: String = "shell:rm",
        target: String = "/var/minis/shared/a",
        segment: String = "rm a",
        level: RiskLevel = RiskLevel.HIGH,
        recovery: Recovery = Recovery.PERMANENT,
        reach: Reach = Reach.SESSION,
    ): ApprovalRequest = ApprovalRequest.of(
        RiskVerdict(level, reach, recovery, rule, target, emptyList(), segment),
        Purpose("geçici dosyaları toparla", Purpose.Source.AGENT_STATED),
        listOf(session),
    )

    @Test
    fun `ayni kural ayni hedef tek kartta toplanir`() {
        val q = ApprovalQueue()
        q.add(req(segment = "rm a/1.txt"))
        q.add(req(segment = "rm a/2.txt"))
        q.add(req(segment = "rm a/3.txt"))
        val cards = q.cards()
        assertEquals(1, cards.size)
        assertEquals(3, cards.single().items.size)
        assertTrue(cards.single().bulkAllowed)
    }

    @Test
    fun `farkli hedefler birlestirilmez`() {
        // KARAR: iki ayrı dizin iki ayrı karardır. Tek "hepsine izin ver"
        // düğmesi kullanıcının vermediği kararı vermiş gibi gösterir.
        val q = ApprovalQueue()
        q.add(req(target = "/var/minis/shared/a", segment = "rm a/1"))
        q.add(req(target = "/var/minis/shared/b", segment = "rm b/1"))
        assertEquals(2, q.cards().size)
        assertFalse(q.cards().any { it.bulkAllowed })
    }

    @Test
    fun `tek kartta en fazla uc islem toplanir`() {
        // Daha fazlası "hepsine" düğmesini refleks hâline getirir.
        val q = ApprovalQueue()
        (1..5).forEach { q.add(req(segment = "rm a/$it")) }
        assertFalse(q.cards().single().bulkAllowed)
    }

    @Test
    fun `en tehlikeli kart en ustte`() {
        val q = ApprovalQueue()
        q.add(req(rule = "git:reset-hard", level = RiskLevel.MEDIUM, recovery = Recovery.RECOVERABLE))
        q.add(req(rule = "shell:rm-recursive", level = RiskLevel.HIGH, recovery = Recovery.PERMANENT))
        assertEquals("shell:rm-recursive", q.cards().first().items.first().verdict.ruleId)
    }

    @Test
    fun `ayni cagri iki kez kuyruga girmez`() {
        val q = ApprovalQueue()
        val r = req()
        q.add(r)
        q.add(r)
        assertEquals(1, q.size())
    }

    @Test
    fun `kart metni bes satiri da tasir`() {
        // Kullanıcının istediği sözleşme: ne / neden / geri dönüş / amaç / yetki.
        val labels = req().lines().map { it.label }
        assertEquals(listOf("NE", "NEDEN RİSKLİ", "GERİ DÖNÜŞ", "AMAÇ", "YETKİ"), labels)
    }

    @Test
    fun `amac belirtilmediyse kart bunu soyler`() {
        val r = ApprovalRequest.of(
            RiskVerdict(RiskLevel.HIGH, Reach.SESSION, Recovery.PERMANENT, "shell:rm", "/x", emptyList(), "rm x"),
            Purpose(null, Purpose.Source.MISSING),
            listOf(session),
        )
        val amac = r.lines().first { it.label == "AMAÇ" }
        assertEquals("amaç belirtilmedi", amac.value)
        assertTrue(amac.strong) // zayıf amaç görsel olarak işaretlenir
    }

    @Test
    fun `kalici islem icin kalicilik notu yazilir`() {
        val r = req()
        assertTrue(r.persistenceNote()?.contains("yalnız bu sohbette") == true)
    }
}
