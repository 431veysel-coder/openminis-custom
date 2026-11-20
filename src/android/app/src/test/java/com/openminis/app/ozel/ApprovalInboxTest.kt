package com.openminis.app.ozel

import com.openminis.app.policy.ApprovalRequest
import com.openminis.app.policy.ApprovalScope
import com.openminis.app.policy.Decision
import com.openminis.app.policy.Purpose
import com.openminis.app.policy.Reach
import com.openminis.app.policy.Recovery
import com.openminis.app.policy.RiskLevel
import com.openminis.app.policy.RiskReason
import com.openminis.app.policy.RiskVerdict
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37096] `ApprovalInbox` — .15'te hiç testi olmayan kodun testi.
 *
 * Neden bu dosya var: bir turu beş dakika bekletebilen, cevabı yanlış karta
 * bağlayabilen ve iptal edildiğinde kartı ekranda bırakabilen mantık
 * `PolicyGate`'in içinde, Android bağımlılıklarıyla sarılı duruyordu. Orada
 * test edilemiyordu; buraya taşındı ve aşağıdaki dört davranış sözleşme oldu.
 */
class ApprovalInboxTest {

    private fun verdict(ruleId: String = "shell:rm", target: String = "/var/minis/shared") =
        RiskVerdict(
            level = RiskLevel.HIGH,
            reach = Reach.SESSION,
            recovery = Recovery.PERMANENT,
            ruleId = ruleId,
            targetPrefix = target,
            reasons = listOf(RiskReason(RiskReason.Kind.EFFECT, "test")),
            segment = "rm -rf $target",
        )

    private fun request(id: String = "shell:rm#1"): ApprovalRequest =
        ApprovalRequest.of(verdict(), Purpose(null, Purpose.Source.MISSING), listOf(ApprovalScope.Global))
            .copy(id = id)

    @Test
    fun `cevap gelirse ask o cevabi doner`() = runTest {
        val inbox = ApprovalInbox(timeoutMs = 0)
        val req = request()
        val waiting = async { inbox.ask(req) }
        // Kuyruk, cevaptan önce kartı göstermiş olmalı.
        delay(10)
        assertEquals(1, inbox.pendingCount)
        assertTrue(inbox.answer(req.id, ApprovalInbox.Answer(Decision.ALLOW, ApprovalScope.Global)))

        val answer = waiting.await()
        assertEquals(Decision.ALLOW, answer?.decision)
        assertEquals(ApprovalScope.Global, answer?.scope)
        assertEquals(0, inbox.pendingCount)
    }

    @Test
    fun `cevap gelmezse zaman asimi null doner ve kuyruk bosalir`() = runTest {
        val inbox = ApprovalInbox(timeoutMs = 50)
        val answer = inbox.ask(request())
        assertNull(answer)
        assertEquals(0, inbox.pendingCount)
    }

    @Test
    fun `zaman asimindan sonra gelen cevap yutulur`() = runTest {
        val inbox = ApprovalInbox(timeoutMs = 20)
        val req = request()
        assertNull(inbox.ask(req))
        // Kart artık yok: cevap hiçbir yere yazılmamalı, exception da atmamalı.
        assertEquals(false, inbox.answer(req.id, ApprovalInbox.Answer(Decision.ALLOW, null)))
    }

    @Test
    fun `ikinci cevap ilkini ezmez`() = runTest {
        val inbox = ApprovalInbox(timeoutMs = 0)
        val req = request()
        val waiting = async { inbox.ask(req) }
        delay(10)
        inbox.answer(req.id, ApprovalInbox.Answer(Decision.ALLOW, ApprovalScope.Global))
        // Çift dokunuş: reddetmeye çalışmak kararı değiştirmemeli.
        assertEquals(false, inbox.answer(req.id, ApprovalInbox.Answer(Decision.DENY, null, "geç kaldı")))
        assertEquals(Decision.ALLOW, waiting.await()?.decision)
    }

    @Test
    fun `hepsini reddet bekleyen her karti kapatir`() = runTest {
        val inbox = ApprovalInbox(timeoutMs = 0)
        val a = request("shell:rm#a")
        val b = request("shell:move#b")
        val wa = async { inbox.ask(a) }
        val wb = async { inbox.ask(b) }
        delay(10)
        assertEquals(2, inbox.pendingCount)

        assertEquals(2, inbox.refuseAll())
        assertEquals(Decision.DENY, wa.await()?.decision)
        assertEquals(Decision.DENY, wb.await()?.decision)
        assertEquals(0, inbox.pendingCount)
    }

    @Test
    fun `ayni kimlikli kart kuyrukta birlesir`() = runTest {
        val inbox = ApprovalInbox(timeoutMs = 0)
        val req = request("shell:rm#same")
        val first = async { inbox.ask(req) }
        delay(5)
        val second = async { inbox.ask(req) }
        delay(10)
        // İki çağrı, tek kart: kullanıcıya aynı soru iki kez gösterilmez.
        assertEquals(1, inbox.pendingCount)

        inbox.answer(req.id, ApprovalInbox.Answer(Decision.ALLOW, null))
        first.await()
        second.await()
        assertEquals(0, inbox.pendingCount)
    }

    @Test
    fun `zaman asimi gerekcesi kart metniyle ayni sayiyi kullanir`() {
        assertEquals("kullanıcı 5 dakika içinde cevap vermedi", ApprovalInbox(5 * 60 * 1000L).timeoutReason())
        assertEquals("kullanıcı 1 dakika içinde cevap vermedi", ApprovalInbox(60_000L).timeoutReason())
    }
}
