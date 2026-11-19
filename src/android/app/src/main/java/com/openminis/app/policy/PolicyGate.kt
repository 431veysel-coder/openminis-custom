package com.openminis.app.policy

import android.content.Context
import com.openminis.app.data.OzelFeatures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Onay bekleyen kart sayısı — arayüz bunu okur. */
    private val _pending = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pending: StateFlow<List<ApprovalRequest>> = _pending.asStateFlow()

    private val waiters = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Answer>>()

    /** Kullanıcının verdiği cevap. */
    data class Answer(val decision: Decision, val scope: ApprovalScope?, val note: String = "")

    const val TIMEOUT_MS: Long = 5 * 60 * 1000

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
        if (!OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.APPROVAL_GATE)) return null
        val args = flatArgs(argsJson)
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
        var decision = ApprovalPolicy.decide(verdict, book, System.currentTimeMillis())
        if (decision is GateDecision.Silent) {
            // A live grant answered it: count the use so the cap can trip.
            (decision as GateDecision.Silent).grant?.let { g ->
                book.consume(g, System.currentTimeMillis())
                persist(ctx, book)
            }
            return decision
        }
        if (decision is GateDecision.Deny) return decision

        val request = (decision as GateDecision.Ask).request
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
    private suspend fun ask(request: ApprovalRequest): Answer? {
        val def = CompletableDeferred<Answer>()
        waiters[request.id] = def
        _pending.value = (_pending.value.filterNot { it.id == request.id } + request)
        try {
            return withTimeoutOrNull(TIMEOUT_MS) { def.await() }
        } finally {
            waiters.remove(request.id)
            _pending.value = _pending.value.filterNot { it.id == request.id }
        }
    }

    /** Arayüzden çağrılır. Cevap yoksa (zaman aşımı) sessizce yutulur. */
    fun answer(requestId: String, answer: Answer) {
        waiters[requestId]?.complete(answer)
    }

    /** Arayüzdeki tüm kartları reddet — kullanıcı "hepsini durdur" derse. */
    fun refuseAll(note: String = "kullanıcı hepsini reddetti") {
        val ids = _pending.value.map { it.id }
        ids.forEach { id -> waiters[id]?.complete(Answer(Decision.DENY, null, note)) }
    }

    // ---------------------------------------------------------------- iç

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
