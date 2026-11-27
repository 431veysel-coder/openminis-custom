package com.openminis.app.policy

/**
 * [OZEL 37080] Politika — "kim, hangi kapsamda, ne kadar süre izin alabilir".
 *
 * ## Tek cümlelik kural
 *
 * **Geri dönüşü olmayan bir iş, oturumdan uzun yaşayan onay alamaz.**
 *
 * Bu kural ürünün tamamını taşıyor. Kullanıcı "bu projede pip kurulumuna izin
 * ver, bir daha sorma" diyebilir — kayıp bir dizin, yeniden kurulur. Ama
 * "bu projede her silmeye izin ver" diyemez, çünkü o izin bir gün yanlış
 * dizinde çalışır ve `minis-spaces/<proje>` boşalır. Kalıcı izin **yalnız
 * geri dönülebilir** işler için var; bu, özelliğin kısıtı değil, varlık sebebi.
 *
 * ## Neden seviye değil de kapsam belirliyor
 *
 * `MEDIUM` bir iş (git reset --hard) projede kalıcı izin alabilir; `HIGH` bir
 * iş (rm -rf shared) alamaz. Seviye ciddiyeti anlatır, kalıcılığı değil.
 * Karıştırılırsa "yüksek risk = daha çok sor" gibi görünür ama asıl mesele
 * "yüksek risk = daha kısa ömür".
 */
object ApprovalPolicy {

    /** Onay kartını hak eden seviye. LOW sessiz geçer — gerekçesi aşağıda. */
    // `const val` yalnız ilkel/String olabilir; enum için `val` (derleme tuzağı).
    val ASK_FROM: RiskLevel = RiskLevel.MEDIUM

    fun decide(verdict: RiskVerdict, book: GrantBook, nowMs: Long): GateDecision {
        if (verdict.ruleId.startsWith("deny:")) {
            val why = verdict.reasons.firstOrNull()?.text ?: "bu işlem her koşulda reddedilir"
            return GateDecision.Deny(verdict.ruleId, why)
        }
        if (verdict.level < ASK_FROM) return GateDecision.Silent(verdict, null)

        val granted = book.lookup(verdict.ruleId, verdict.targetPrefix, nowMs)
        return when (granted?.decision) {
            Decision.ALLOW -> GateDecision.Silent(verdict, granted)
            Decision.DENY -> GateDecision.Deny(
                verdict.ruleId,
                granted.note.ifBlank { "bu kapsamda daha önce reddedildi" },
            )
            null -> GateDecision.Ask(
                ApprovalRequest.of(
                    verdict,
                    Purpose(null, Purpose.Source.MISSING),
                    offeredScopes(verdict, book.session, book.project),
                ),
            )
        }
    }

    /**
     * Kullanıcıya sunulabilecek kapsamlar — dar olan önce.
     *
     * Kart ilk seçeneği önerir; kullanıcı "hepsine" basmak zorunda kalmaz.
     * Kalıcı işte liste tek elemanlıdır ve kart bunu **yazar**: "bu iş geri
     * alınamadığı için yalnız bu sohbet için hatırlanır". Sessizce kısıtlamak
     * kullanıcıya "neden projede sormadı" dedirtir.
     */
    fun offeredScopes(
        verdict: RiskVerdict,
        session: ApprovalScope.Session,
        project: ApprovalScope.Project? = null,
    ): List<ApprovalScope> {
        val out = mutableListOf<ApprovalScope>()
        when (verdict.recovery) {
            Recovery.PERMANENT -> {
                out += session
            }
            Recovery.RECOVERABLE -> {
                out += session
                if (project != null && verdict.reach <= Reach.SANDBOX) out += project
            }
            Recovery.UNDOABLE -> {
                out += session
                if (project != null) out += project
                if (verdict.reach <= Reach.SANDBOX) out += ApprovalScope.Global
            }
        }
        return out
    }

    /** Kapsama göre ömür. `null` = kapsam bitene kadar. */
    fun ttlMs(scope: ApprovalScope, verdict: RiskVerdict): Long? = when (scope) {
        is ApprovalScope.Session -> null
        is ApprovalScope.Project -> 30L * 24 * 3600 * 1000
        ApprovalScope.Global -> if (verdict.recovery == Recovery.RECOVERABLE) {
            90L * 24 * 3600 * 1000
        } else {
            180L * 24 * 3600 * 1000
        }
    }

    /**
     * Kullanım tavanı — sessiz çoğalmaya karşı.
     *
     * Kalıcı silmede tavan düşük (20): ajan bir döngüye girip aynı komutu 300
     * kez çalıştırırsa 21. kez yeniden sorulur. Kullanıcı o noktada "bir şey
     * ters gidiyor" sinyalini alır — asıl istenen bu.
     */
    fun maxUses(verdict: RiskVerdict): Int? = when (verdict.recovery) {
        Recovery.PERMANENT -> 20
        Recovery.RECOVERABLE -> 50
        Recovery.UNDOABLE -> null
    }

    /**
     * Verilen cevaptan kaydı üretir.
     *
     * @param unlimited [37129] `true` ise **kullanım sayacı yoktur**. Yalnız
     *   mod düzeyinde verilen izinler için (`ALLOW_ALL` / `SETTINGS_ONLY`).
     *
     * ## Neden bu parametre var (kullanıcının şikâyeti)
     *
     * *"onay istiyor sesli olarak, onaylanmış olsa dahi istiyor"*.
     *
     * Kök sebep buradaydı: mod izni de normal cevapla aynı `maxUses`'ı
     * alıyordu (PERMANENT 20, RECOVERABLE 50) ve her kullanımda tüketiliyordu.
     * Yani kullanıcı "bu sınıfı bir daha sorma" dedikten **20 komut sonra**
     * kayıt düşüyor, kapı yeniden onay üretiyor, ses yeniden konuşuyordu.
     *
     * Ayrım şu: bir **soruya verilen cevap** sayılıdır (kullanıcı tek bir iş
     * için izin verdi, belli bir sayıdan sonra tekrar bakmak doğru). Bir
     * **mod kararı** sayılı değildir: kullanıcı bir sınıf hakkında bir kez
     * karar verdi ve o karar değişene kadar geçerlidir.
     */
    fun grantFor(
        request: ApprovalRequest,
        scope: ApprovalScope,
        decision: Decision,
        nowMs: Long,
        note: String = "",
        unlimited: Boolean = false,
    ): Grant {
        val v = request.verdict
        // [H-017] `unlimited` artık **ömürsüz** demek, yalnız sayaçsız değil.
        //
        // Kullanıcının sözü: *"Tek seferlik izni isteyecek, izni vereceğiz,
        // ondan sonra bir daha izin istemeyecek; sürekli her şeyi izni
        // olacaktır."* Eski hâlde mod kararı sayaçtan kurtuluyordu ama
        // `ttlMs` (Global'de 90/180 gün) duruyordu — yani 180 gün sonra kapı
        // sessizce yeniden kart üretirdi. Verilmiş bir "bir daha sorma"nın
        // tarihle kendiliğinden iptal olması, kararı unutmaktır.
        //
        // Bu yalnız **mod kararı** için geçerli: soruya verilen cevap
        // (`unlimited = false`) ömrünü korur, çünkü orada kullanıcı tek bir
        // iş için izin verdi.
        val ttl = if (unlimited) null else ttlMs(scope, v)
        return Grant(
            ruleId = v.ruleId,
            targetPrefix = v.targetPrefix,
            scope = scope,
            decision = decision,
            grantedAtMs = nowMs,
            expiresAtMs = ttl?.let { nowMs + it },
            maxUses = if (!unlimited && decision == Decision.ALLOW) maxUses(v) else null,
            note = note,
        )
    }
}
