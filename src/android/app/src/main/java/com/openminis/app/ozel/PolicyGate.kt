package com.openminis.app.ozel

import android.content.Context
import com.openminis.app.policy.ApprovalPolicy
import com.openminis.app.policy.ApprovalRequest
import com.openminis.app.policy.ApprovalScope
import com.openminis.app.policy.Decision
import com.openminis.app.policy.GateDecision
import com.openminis.app.policy.GrantBook
import com.openminis.app.policy.RiskClassifier
import com.openminis.app.policy.RiskRules
import com.openminis.app.policy.RiskReason
import com.openminis.app.policy.RiskVerdict
import com.openminis.app.policy.Reach
import com.openminis.app.policy.Recovery
import com.openminis.app.policy.RiskLevel
import com.openminis.app.data.OzelFeatures
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * [OZEL 37080] Onay köprüsü — sınıflandırıcı ile araç yürütücüsü arasındaki tek kapı.
 *
 * ## Neden tek bir `suspend gate` ve başka bir şey yok
 *
 * Ajan `shell_execute` çağırdığında üç şey olabilir: geçer, sorulur, reddedilir.
 * Bu kararın **çağrı yerinde** verilmesi şart — çağrıdan önceki bir turda
 * verilirse izin, komut değiştikten sonra da geçerli olur. Bu yüzden kapı
 * `executeTool`'un içinde, `when (name)` bloğundan hemen önce duruyor.
 *
 * ## Neden beklemek bloklamıyor ama turu durduruyor
 *
 * Kart açıkken ajanın iş yapmaya devam etmesi, onay mekanizmasını süs yapar:
 * model "izin gelene kadar başka iş yapayım" der, sonra izin gelir ama artık
 * gereksizdir. Bu yüzden `await` gerçekten bekliyor. Bedeli: kullanıcı 5 dakika
 * cevap vermezse tur zaman aşımına düşer ve **nedeni yazılır** (sessiz iptal yok).
 *
 * ## Neden süre sınırı var
 *
 * Telefon cebinde, ekran kapalı, ajan bekliyor — sonsuz bekleyen bir tur, pil
 * ve süreç ömrü demek. 5 dakika sonunda `Deny("onay verilmedi")` döner ve model
 * bunu görüp etrafından dolaşabilir ya da kullanıcıya sorabilir.
 */
object PolicyGate {

    /**
     * [37096] Bekleme odası ayrı sınıfta: `ApprovalInbox` Android'siz, yani
     * zaman aşımı ve çift cevap test edilebilir. Burası yalnız sınıflandırma
     * ile kuyruğu birleştiriyor.
     */
    private val inbox = ApprovalInbox(TIMEOUT_MS)

    /** Onay bekleyen kartlar — arayüz bunu okur. */
    val pending: StateFlow<List<ApprovalRequest>> = inbox.pending

    /** Kullanıcının verdiği cevap. */
    data class Answer(val decision: Decision, val scope: ApprovalScope?, val note: String = "")

    const val TIMEOUT_MS: Long = ApprovalInbox.DEFAULT_TIMEOUT_MS

    /** planVerdict'in Context'e ihtiyacı var; gate() girişinde set edilir. */
    private var appContext: Context? = null

    /**
     * Kapı. `null` dönerse araç hiç sınıflandırılmadı (alakasız araç) — geçer.
     * Dönen [GateDecision.Deny] ise çağrı yapılmamalı.
     */
    suspend fun gate(
        ctx: Context,
        sessionId: String,
        folderId: String?,
        toolName: String,
        argsJson: String,
    ): GateDecision? {
        appContext = ctx
        if (!OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.APPROVAL_GATE)
            && !OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.PLAN_PREVIEW)
        ) {
            return null
        }
        val args = flatArgs(argsJson)

        // [37083] Plan preview for a long run — reuses this queue on purpose.
        // A second card type would mean a second waiting mechanism, a second
        // persistence story and a second "remember this" rule; the difference
        // here is only WHAT the card says, not how the answer is obtained.
        planVerdict(toolName, args)?.let { v ->
            val book = bookFor(ctx, sessionId, folderId)
            val decided = ApprovalPolicy.decide(v, book, System.currentTimeMillis())
            if (decided is GateDecision.Silent) return decided
            if (decided is GateDecision.Deny) return decided
            val request = (decided as GateDecision.Ask).request
            val answer = ask(request)
                ?: return GateDecision.Deny(v.ruleId, "plan onaylanmadı; iş başlatılmadı")
            val grant = ApprovalPolicy.grantFor(
                request, answer.scope ?: ApprovalScope.Session(sessionId),
                answer.decision, System.currentTimeMillis(), answer.note,
            )
            book.put(grant)
            persist(ctx, book)
            if (answer.decision != Decision.ALLOW) {
                return GateDecision.Deny(v.ruleId, "kullanıcı planı onaylamadı; iş başlatılmadı")
            }
        }

        val verdict = when (toolName) {
            "shell_execute" -> {
                val cmd = args["command"] ?: return null
                classifier(sessionId).classifyShell(cmd)
            }
            "file_write", "file_edit" -> classifier(sessionId).classifyTool(toolName, args) ?: return null
            else -> return null
        }
        if (verdict.level < ApprovalPolicy.ASK_FROM) return GateDecision.Silent(verdict, null)

        val book = bookFor(ctx, sessionId, folderId)
        val now = System.currentTimeMillis()
        val mode = ApprovalSettings.mode(ctx)
        val decision = ApprovalPolicy.decide(verdict, book, now)
        if (decision is GateDecision.Silent) {
            // A live grant answered it: count the use so the cap can trip.
            decision.grant?.let { g ->
                book.consume(g, now)
                persist(ctx, book)
            }
            return decision
        }
        if (decision is GateDecision.Deny) return decision

        val request = (decision as GateDecision.Ask).request

        // [37116] Onay modu: "sohbette sorma, bir kez Ayarlar'da karar ver."
        //
        // Mod `ASK_EACH` ise bu blok hiç çalışmaz. Diğer iki modda, çağrı
        // başına değil **kural** başına bir kez karar verilir: kural
        // `autoGrants` diyorsa uygulama geneli (`Global`) izin yazılır ve iş
        // sessiz geçer. Sonraki bütün sohbetlerde aynı kural yine sessizdir,
        // çünkü kayıt diskte.
        //
        // Neden burada "tembel" yazılıyor da Ayarlar'da toplu yazılmıyor:
        // kayıtların ömrü var (`ApprovalPolicy.ttlMs`) ve sonradan eklenen bir
        // kural eski bir toplu yazımın içinde olmaz. Tembel yazım iki durumu
        // da kendiliğinden onarır; "süresi bitti, yine soruyor" hatası doğmaz.
        //
        // `deny:` sınıfı buradan **geçemez**: `autoGrants` onları her modda
        // `false` döner, ayrıca `ApprovalPolicy.decide` zaten `Deny` üretip
        // yukarıda döndü.
        val rule = RiskRules.byId(verdict.ruleId)
        if (rule != null && mode.autoGrants(rule)) {
            val grant = ApprovalPolicy.grantFor(
                request = request,
                scope = ApprovalScope.Global,
                decision = Decision.ALLOW,
                nowMs = now,
                note = "Ayarlar › İzinler · mod: ${mode.key}",
            )
            book.put(grant)
            book.consume(grant, now)
            persist(ctx, book)

            // [37118] Kart kalktı, **haber** kalkmadı.
            //
            // Geri alınamaz ya da sandbox dışı bir iş artık beklenmiyor ama
            // söyleniyor: telefon tek cümle söyler, titrer, bildirim bırakır
            // ve iş devam eder. Kullanıcının şikâyeti "onay veremiyorum"du;
            // "ne olduğunu bilmiyorum" değil. Bu satır olmadan `ALLOW_ALL`,
            // sessiz bir silme makinesi olurdu.
            if (mode.notifyOnly(rule)) {
                runCatching { ApprovalVoice.notice(ctx, request) }
            }
            return GateDecision.Silent(verdict, grant)
        }
        val answer = ask(request)
            ?: return GateDecision.Deny(
                verdict.ruleId,
                "kullanıcı ${TIMEOUT_MS / 60_000} dakika içinde cevap vermedi; komut çalıştırılmadı",
            )

        val scope = answer.scope ?: ApprovalScope.Session(sessionId)
        val grant = ApprovalPolicy.grantFor(request, scope, answer.decision, System.currentTimeMillis(), answer.note)
        book.put(grant)
        persist(ctx, book)
        return if (answer.decision == Decision.ALLOW) GateDecision.Silent(verdict, grant)
        else GateDecision.Deny(verdict.ruleId, answer.note.ifBlank { "kullanıcı reddetti" })
    }

    /** Kartı kuyruğa koyar ve cevabı bekler. Kuyrukta aynı soru birleşir. */
    private suspend fun ask(request: ApprovalRequest): Answer? =
        inbox.ask(request)?.let { Answer(it.decision, it.scope, it.note) }

    /**
     * Arayüzden çağrılır. Kart artık yoksa (zaman aşımı / iptal) `false`
     * döner; cevap yutulmaz.
     */
    fun answer(requestId: String, answer: Answer): Boolean =
        inbox.answer(requestId, ApprovalInbox.Answer(answer.decision, answer.scope, answer.note))

    /** Arayüzdeki tüm kartları reddet — kullanıcı "hepsini durdur" derse. */
    fun refuseAll(note: String = "kullanıcı hepsini reddetti"): Int = inbox.refuseAll(note)

    /** Tur iptal edildi: bekleyen kartları düşür. Kaç kart kapatıldığını döner. */
    fun cancelPending(): Int = inbox.clear()

    // ---------------------------------------------------------------- iç

    /**
     * [37083] A long-run estimate from arguments that already exist.
     *
     * `subagent_task.max_minutes` is a real declared bound, and `team_run`'s
     * `max_rounds` bounds the rounds — both are honest inputs, unlike a guess
     * derived from the prompt's length. With no number to read, no card is
     * shown: inventing an estimate would make the card a decoration.
     */
    private fun planVerdict(toolName: String, args: Map<String, String>): RiskVerdict? {
        val ctx = this.appContext ?: return null
        if (!OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.PLAN_PREVIEW)) return null
        val minutes: Int
        val tokens: Long
        when (toolName) {
            "subagent_task" -> {
                if (args["action"] != null && args["action"] != "delegate") return null
                minutes = args["max_minutes"]?.toIntOrNull() ?: com.openminis.app.agent.jobs.HelperRunner.DEFAULT_MINUTES
                tokens = 0L
            }
            "team_run" -> {
                val rounds = args["max_rounds"]?.toIntOrNull() ?: 12
                minutes = rounds * 2 // one council round is two model passes
                tokens = 0L
            }
            else -> return null
        }
        val estimate = com.openminis.app.plan.RunEstimate(
            minutes = minutes,
            tokens = tokens,
            rounds = if (toolName == "team_run") (args["max_rounds"]?.toIntOrNull() ?: 12) else 1,
            models = if (toolName == "team_run") 5 else 1,
            steps = listOf(
                if (toolName == "team_run") "Ekip koşusu: ${args["team"].orEmpty()}" else "Alt ajan: ${args["agent"].orEmpty()}",
                args["tool_title"].orEmpty(),
            ).filter { it.isNotBlank() },
        )
        val reason = com.openminis.app.plan.PlanGate.shouldPreview(estimate, com.openminis.app.plan.PlanThresholds())
            ?: return null
        return RiskVerdict(
            level = RiskLevel.MEDIUM,
            reach = Reach.SANDBOX,
            recovery = Recovery.UNDOABLE,
            ruleId = "plan:long-run",
            targetPrefix = toolName,
            reasons = listOf(
                com.openminis.app.policy.RiskReason(
                    com.openminis.app.policy.RiskReason.Kind.EFFECT,
                    "$reason — plan: ${estimate.steps.firstOrNull().orEmpty()}",
                ),
                com.openminis.app.policy.RiskReason(
                    com.openminis.app.policy.RiskReason.Kind.TARGET,
                    "${estimate.rounds} tur · ${estimate.models} model",
                ),
            ),
            segment = args["tool_title"].orEmpty(),
        )
    }

    private fun classifier(sessionId: String): RiskClassifier {
        val root = "/var/minis/minis-sessions/$sessionId"
        return RiskClassifier(root)
    }

    /**
     * Okuma yolu: kapsam anahtarı -> kayıtlar. `Grant` kendi kapsamını taşıyor,
     * ama dosyadan gelen kayıtlar anahtarı bilmiyor; burada yeniden eşleniyor.
     */
    private fun bookFor(ctx: Context, sessionId: String, folderId: String?): GrantBook {
        val all = GrantStorage.load(ctx)
        val book = GrantBook(
            ApprovalScope.Session(sessionId),
            folderId?.let { ApprovalScope.Project(it) },
        )
        all.forEach { (key, grants) -> ApprovalScope.parse(key)?.let { book.replaceScope(it, grants) } }
        return book
    }

    private fun persist(ctx: Context, book: GrantBook) {
        val byScope = book.all().groupBy { it.scope.key }
        GrantStorage.save(ctx, byScope)
    }

    /** Araç argümanlarını düz sözlüğe indirger; yalnız dize alanları. */
    private fun flatArgs(argsJson: String): Map<String, String> = try {
        val o = JSONObject(argsJson)
        buildMap {
            o.keys().forEach { k ->
                val v = o.opt(k)
                if (v is String) put(k, v)
            }
        }
    } catch (_: Throwable) {
        emptyMap()
    }
}
