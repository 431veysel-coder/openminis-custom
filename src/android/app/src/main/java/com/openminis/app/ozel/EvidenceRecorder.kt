package com.openminis.app.ozel

import android.content.Context
import com.openminis.app.agent.evidence.ClaimCheck
import com.openminis.app.agent.evidence.EvidenceGate
import com.openminis.app.agent.evidence.Proof
import com.openminis.app.agent.evidence.ProofKind
import com.openminis.app.data.OzelFeatures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * [37087] Kanıt toplayıcısı — "bitti" beyanını gerçek çıktıya bağlar.
 *
 * ## Neden engellemiyor, geciktiriyor
 *
 * Sert ret, doğrulanamayan işleri ("kullanıcı beğendi mi") kilitler. Bunun
 * yerine: tur sonunda beyan kanıtsızsa bir sonraki turun sistem istemine tek
 * satırlık bir **hatırlatıcı** eklenir ("şu komutu çalıştır, çıktısını göster").
 * Model bir tur daha çalışır ve kanıtı üretir. İki turdan sonra hâlâ kanıt
 * yoksa not düşülür ve geçilir — sonsuz döngü yok.
 *
 * ## Neden kanıt araç çıktısından geliyor
 *
 * Modelin "testler geçti" cümlesi kanıt değil, iddia. Kanıt `shell_execute`'un
 * çıkış kodudur, `verify` aracının PASS'idir, diskte var olan dosyadır. Bu
 * yüzden toplayıcı yalnız araç sonuçlarını görüyor.
 */
object EvidenceRecorder {

    private const val MAX_PER_SESSION = 40

    private val proofs = ConcurrentHashMap<String, MutableList<Proof>>()
    private val retries = ConcurrentHashMap<String, Int>()
    private val pending = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Bir sonraki turun istemine eklenecek hatırlatıcı (oturum başına). */
    val nudge: StateFlow<Map<String, String>> = pending.asStateFlow()

    private fun list(sessionId: String): MutableList<Proof> =
        proofs.getOrPut(sessionId) { mutableListOf() }

    /**
     * Araç çağrısı başarıyla bittiğinde çağrılır.
     *
     * [subject] iddia ile eşleşmesi için konuyu taşır: `shell_execute` için
     * komutun ilk kelimesi, `file_*` için dosya yolu. Konusuz bırakmak, her
     * kanıtı her iddiaya sayar hâle getirir — o zaman kapı hiçbir şey yapmaz.
     */
    fun note(
        ctx: Context,
        sessionId: String,
        tool: String,
        success: Boolean,
        output: String,
        turnIndex: Int,
        subjectHint: String = "",
    ) {
        if (!success) return
        runCatching {
            val kind = when {
                tool == "verify" -> ProofKind.VERIFY_TOOL
                tool == "shell_execute" && looksLikeTestRun(output) -> ProofKind.TEST_PASS
                tool == "shell_execute" && looksLikeBuild(output) -> ProofKind.BUILD_OK
                tool == "shell_execute" -> ProofKind.COMMAND_OK
                tool == "browser_use" -> ProofKind.SCREENSHOT
                tool == "file_write" || tool == "file_edit" -> ProofKind.FILE_EXISTS
                else -> return@runCatching
            }
            val subject = subjectHint
            val l = list(sessionId)
            l += Proof(kind, turnIndex, System.currentTimeMillis(), output.take(200), subject)
            if (l.size > MAX_PER_SESSION) l.removeAt(0)
        }
    }

    /**
     * Tur sonunda çağrılır. `true` dönerse bir sonraki tura hatırlatıcı eklendi.
     */
    fun checkTurnEnd(
        ctx: Context,
        sessionId: String,
        claimText: String,
        turnIndex: Int,
        pendingProofSubject: String = "",
    ): ClaimCheck? {
        if (!OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.EVIDENCE_GATE)) return null
        if (!looksLikeCompletionClaim(claimText)) return null

        val result = EvidenceGate.check(claimText, list(sessionId), turnIndex)
        when (result) {
            is ClaimCheck.Accepted -> {
                retries.remove(sessionId)
                pending.value = pending.value - sessionId
            }
            is ClaimCheck.Rejected -> {
                val tries = (retries[sessionId] ?: 0) + 1
                retries[sessionId] = tries
                if (tries <= 2) {
                    // Nudge, not a block: the model gets one more turn with the
                    // exact command it should run. Two attempts, then we stop
                    // asking — a gate that loops forever is a gate the user
                    // turns off on day one.
                    val nudge = buildString {
                        append("<system-reminder>")
                        append("Tamamlandı beyanı için kanıt yok (${result.reason}). ")
                        append(result.howToProve)
                        append(" Sonucu göster, sonra bitir.")
                        append("</system-reminder>")
                    }
                    pending.value = pending.value + (sessionId to nudge)
                } else {
                    retries.remove(sessionId)
                    pending.value = pending.value - sessionId
                }
            }
        }
        return result
    }

    fun forget(sessionId: String) {
        proofs.remove(sessionId)
        retries.remove(sessionId)
        pending.value = pending.value - sessionId
    }

    // ---------------------------------------------------------------- iç

    private fun looksLikeTestRun(out: String): Boolean {
        val o = out.lowercase()
        return (o.contains("passed") || o.contains("tests run") || o.contains("ok (")) &&
            !o.contains("failed")
    }

    private fun looksLikeBuild(out: String): Boolean {
        val o = out.lowercase()
        return o.contains("build successful") || o.contains("build succeeded") ||
            o.contains("build success")
    }

    private fun looksLikeCompletionClaim(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.lowercase()
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        // Folded forms only: the text and the cues go through the same folding,
        // so "tamamlandı" and "tamamlandi" cannot diverge.
        val cues = listOf(
            "tamamlandi", "bitti", "bitirildi", "hazir", "basarili",
            "done", "completed", "finished", "tests pass", "all tests pass",
            "duzeltildi", "fixed", "it works", "calisiyor",
        )
        return cues.any { t.contains(it) }
    }
}
