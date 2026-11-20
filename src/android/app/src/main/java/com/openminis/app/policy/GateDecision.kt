package com.openminis.app.policy

/**
 * [OZEL 37080] Kapı kararı — ajanın ve arayüzün gördüğü tek nesne.
 *
 * Üç sonuç var, dördüncüsü yok:
 *
 * - [Silent] bir iş geçti. [grant] doluysa "daha önce izin verilmişti" demektir;
 *   arayüz bunu sessiz bir rozetle gösterir, kart açmaz.
 * - [Ask] kullanıcı bekleniyor. Ajan bu sırada **durur** (kuyrukta bekler), bu
 *   yüzden [ApprovalRequest] tek başına taşınabilir olmalı.
 * - [Deny] geçmeyecek. Gerekçe ajana **aynen** döner; ajanın işi bırakması
 *   değil, etrafından dolaşması beklenir. "İzin yok" cevabı gerekçesiz
 *   verilirse model aynı komutu farklı yazıp tekrar dener — döngü.
 */
sealed class GateDecision {

    abstract fun allowsRun(): Boolean

    data class Silent(val verdict: RiskVerdict, val grant: Grant?) : GateDecision() {
        override fun allowsRun(): Boolean = true
        /** Daha önce verilmiş izinle geçti mi? Ayarlardaki sayaç bunu kullanır. */
        val viaGrant: Boolean get() = grant != null
    }

    data class Ask(val request: ApprovalRequest) : GateDecision() {
        override fun allowsRun(): Boolean = false
    }

    data class Deny(val ruleId: String, val reason: String) : GateDecision() {
        override fun allowsRun(): Boolean = false
    }

    /**
     * Ajana dönen metin.
     *
     * [Ask] için ajan "onay bekleniyor" görür ve **aynı komutu tekrar
     * denemez**; kullanıcı cevabı geldiğinde çağrı devam eder. Bu ayrım
     * önemli: eskiden `user_ask` bloklamadığı için model cevabı beklemeden
     * devam edip tahmin ediyordu.
     */
    fun forAgent(): String = when (this) {
        is Silent -> if (grant == null) "" else
            "(daha önce verilen izinle geçti: ${grant.ruleId}${if (grant.targetPrefix.isEmpty()) "" else " · ${grant.targetPrefix}"})"
        is Ask -> buildString {
            append("Onay bekleniyor: ${request.title}. ")
            append("Gerekçe: ${request.lines().first { it.label == "NEDEN RİSKLİ" }.value}. ")
            append("Aynı komutu yeniden denemeyin; kullanıcı cevabı gelince devam eder.")
        }
        is Deny -> buildString {
            append("Reddedildi ($ruleId): $reason. ")
            append("Bu işlemi farklı yazarak tekrar denemeyin. ")
            append("Amacınız buysa kullanıcıya sorun (user_ask) ya da kapsamı daraltın.")
        }
    }
}
