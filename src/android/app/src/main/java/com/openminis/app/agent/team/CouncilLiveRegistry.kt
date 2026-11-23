package com.openminis.app.agent.team

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [MINIS-OZEL 11.5 · 37064] What is happening in every running council, in one
 * place the UI can observe.
 *
 * ## Why a process-level registry and not a ViewModel
 *
 * A council run outlives every screen that shows it: the user opens the run,
 * leaves, comes back. The run itself is owned by `team_run` inside the parent
 * turn, not by a screen, so the screen cannot own the state. This is the same
 * shape `AgentJobRegistry` already uses for sub agents and scheduled jobs
 * (`StateFlow<Map<…>>` + a completion flow), and reusing that shape means one
 * mental model for "background work" instead of two.
 *
 * ## What it does NOT hold
 *
 * No transcripts, no tool output, no report text. Those live in the member's own
 * child session (readable through the existing read-only transcript screen) and in
 * `TeamRunStore` (the finished record). Holding copies here would duplicate
 * megabytes per run for data the app can already show, and would drift from the
 * originals the moment anything re-read them.
 *
 * The one thing it holds that exists nowhere else is the **member name → session
 * id** map: without it the UI cannot open a member's live session at all, because
 * the runtime that made it is inside a coroutine nothing else can see.
 */
object CouncilLiveRegistry {

    /** One member's live state, as the run screen needs it. */
    data class MemberLive(
        val name: String,
        val role: String,
        val sessionId: String? = null,
        /** Last claim this member stated, trimmed for the card. */
        val claim: String = "",
        val absent: Boolean = false,
        val absentWhy: String = "",
        /** Tool the member is running right now, if any. */
        val tool: String? = null,
    )

    data class LiveRun(
        val runId: String,
        val teamId: String,
        val teamName: String,
        val task: String,
        val startedAtMs: Long,
        val members: Map<String, MemberLive> = emptyMap(),
        val round: Int = 0,
        val roundsPlanned: Int = 0,
        /**
         * [37099d] Aynı anda konuşabilen üye sayısı (RAM'e göre).
         *
         * Neden ekranda: 5 üyeli bir ekipte dalga 4 ise 5. üye ikinci turda
         * başlıyor. Bunu söylemeyen bir ekran "neden biri geç yazdı" sorusunu
         * cevapsız bırakır ve kullanıcı koşuyu bozuk sanır.
         */
        val waveSize: Int = 0,
        /**
         * [37099f · §1.2] Ön kontrol uyarısı — boş değilse koşu **eksik
         * üyeyle** başladı.
         *
         * Neden kayıtta: kullanıcı ekibi 5 kişi kurup 2 cevap alınca "konsey
         * bozuk" diyordu. Uyarı koşunun başında bir kez yazılır, ekranın en
         * üstünde durur; sonradan silinmez, çünkü koşunun gerçeği bu.
         */
        val preflightNote: String = "",
        val phase: Phase = Phase.STARTING,
        val tokensUsed: Int = 0,
        /** Claim lines for the agenda tab: id, text, votes. */
        val claims: List<ClaimLine> = emptyList(),
        val dissent: List<String> = emptyList(),
        /**
         * [37051] The task board this run is writing to, keyed by task id.
         *
         * Held as a map rather than a list so a task that changes state updates in
         * place: an agenda row that showed "open" for a task somebody finished two
         * rounds ago is worse than showing nothing.
         */
        val tasks: Map<String, TeamTask> = emptyMap(),
        /**
         * [37099c] İletişim hattı: bir üyenin diğerine sorduğu sorular.
         *
         * Neden burada: `Position.questions` modelde vardı ama hiçbir yüzeye
         * yazılmıyordu. Oda açıldığında gösterilecek tek satır yoktu.
         */
        val questions: List<QuestionLine> = emptyList(),
        /**
         * [37099c] İhtiyaçlar hattı: bir üyenin "bunu sen al" isteği.
         * [tasks] panoya dönüşenlerdir; burada **dönüşmemiş** olanlar da
         * görünür, çünkü kaybolan bir istek sessiz bir iştir.
         */
        val needs: List<NeedLine> = emptyList(),
        val finished: Finished? = null,
    ) {
        val running: Boolean get() = finished == null
    }

    data class ClaimLine(
        val id: String,
        val text: String,
        val support: Int,
        val oppose: Int,
        val uncertain: Int,
        val verdict: String,
        /**
         * [37099c] Karşılaştırma odası: üye → duruş (`support`/`oppose`/`uncertain`).
         *
         * Sayılar (support/oppose) "kaç kişi" sorusunu cevaplıyor; oda ise
         * "kim" sorusunu. İkincisi olmadan tablo çizilemez ve kullanıcı
         * "3'e 2" görüp kimin ne dediğini açamıyordu.
         */
        val stances: Map<String, String> = emptyMap(),
    )

    /**
     * [37099c] İletişim hattındaki tek satır.
     *
     * [answer] ve [answeredRound] [37099d] eklendi. Öncesinde hat yalnız
     * soruyu gösteriyordu: kullanıcı "sordu, peki cevap ne oldu" diye
     * bakıyor, hiçbir şey bulamıyordu. Cevap ayrı bir alanda tutulmuyor —
     * hedef üyenin **sonraki turdaki** pozisyonunun iddiasından eşleniyor
     * ([matchAnswers]). Uydurulmuş bir cevap yazmaktansa boş bırakmak
     * doğrudur: eşleşme yoksa `answer` boş kalır.
     */
    data class QuestionLine(
        val round: Int,
        val from: String,
        val to: String,
        val question: String,
        val answer: String = "",
        val answeredRound: Int = 0,
    )

    /** [37099c] İhtiyaçlar hattındaki tek satır. */
    data class NeedLine(
        val round: Int,
        val from: String,
        val title: String,
        val why: String,
        val expect: String,
    )

    data class Finished(
        val stoppedBy: String,
        val verified: Boolean,
        val rounds: Int,
        val tokensUsed: Int,
    )

    enum class Phase {
        STARTING,
        /** Members answering alone. */
        POSITIONS,
        /** The chair folding positions into claims. */
        CANONICALIZING,
        /** Members arguing over the claim set. */
        DEBATING,
        /** One model writing the report. */
        SYNTHESIZING,
        /** Citation audit and possible one repair. */
        VERIFYING,
        DONE,
    }

    private val _runs = MutableStateFlow<Map<String, LiveRun>>(emptyMap())
    val runs: StateFlow<Map<String, LiveRun>> = _runs.asStateFlow()

    fun start(
        runId: String,
        teamId: String,
        teamName: String,
        task: String,
        memberNames: List<Pair<String, String>>,
        roundsPlanned: Int,
        waveSize: Int = 0,
    ) {
        _runs.update { current ->
            current + (runId to LiveRun(
                runId = runId,
                teamId = teamId,
                teamName = teamName,
                task = task,
                startedAtMs = System.currentTimeMillis(),
                members = memberNames.associate { (name, role) ->
                    name to MemberLive(name = name, role = role)
                },
                roundsPlanned = roundsPlanned,
                waveSize = waveSize,
            ))
        }
    }

    /** The member's child session, as soon as the runtime has created it. */
    fun attachSession(runId: String, memberName: String, sessionId: String) {
        _runs.update { current ->
            val run = current[runId] ?: return@update current
            val member = run.members[memberName] ?: return@update current
            current + (runId to run.copy(
                members = run.members + (memberName to member.copy(sessionId = sessionId)),
            ))
        }
    }

    /** [37099f] Koşu eksik üyeyle başladıysa nedeni kayda yazılır. */
    fun preflight(runId: String, note: String) {
        if (note.isBlank()) return
        _runs.update { current ->
            val run = current[runId] ?: return@update current
            current + (runId to run.copy(preflightNote = note))
        }
    }

    fun phase(runId: String, phase: Phase, round: Int = -1) {
        _runs.update { current ->
            val run = current[runId] ?: return@update current
            current + (runId to run.copy(
                phase = phase,
                round = if (round >= 0) round else run.round,
            ))
        }
    }

    fun memberPosition(runId: String, memberName: String, claim: String, absent: Boolean, why: String) {
        _runs.update { current ->
            val run = current[runId] ?: return@update current
            val member = run.members[memberName] ?: return@update current
            current + (runId to run.copy(
                members = run.members + (memberName to member.copy(
                    claim = claim.take(240),
                    absent = absent,
                    absentWhy = why,
                    tool = null,
                )),
            ))
        }
    }

    /** The tool a member is running, for the "why is it quiet" badge. */
    fun memberTool(runId: String, memberName: String, tool: String?) {
        _runs.update { current ->
            val run = current[runId] ?: return@update current
            val member = run.members[memberName] ?: return@update current
            current + (runId to run.copy(
                members = run.members + (memberName to member.copy(tool = tool)),
            ))
        }
    }

    /**
     * [37099c] İletişim hattı + ihtiyaçlar hattı: tur sonunda üyenin sorduğu
     * sorular ve devrettiği işler.
     *
     * Tek çağrı çünkü ikisi de aynı olaydan (`Position`) geliyor ve ayrı
     * yazılırsa biri güncellenip diğeri unutulur — bu depoda aynı hata sınıfı
     * dört PRoot kurucusunda görüldü.
     *
     * Aynı soru metni tekrar gelirse eklenmez: üyeler bir turda sordukları
     * soruyu sonraki turda yine sorabiliyor (bağlam onlara cevabı göstermediği
     * için), ve ekranda aynı satırın dört kez görünmesi bir hata gibi okunur.
     */
    fun memberAsks(
        runId: String,
        round: Int,
        from: String,
        questions: List<Question>,
        tasks: List<TaskRequest>,
    ) {
        if (questions.isEmpty() && tasks.isEmpty()) return
        _runs.update { current ->
            val run = current[runId] ?: return@update current
            val newQuestions = questions.mapNotNull { q ->
                val to = q.to.trim()
                val text = q.q.trim()
                if (to.isEmpty() || text.isEmpty()) null
                else QuestionLine(round, from, to, text.take(300))
            }.filter { line ->
                run.questions.none {
                    it.from == line.from && it.to == line.to && it.question == line.question
                }
            }
            val newNeeds = tasks.mapNotNull { tr ->
                val title = tr.title.trim()
                if (title.isEmpty()) null
                else NeedLine(round, from, title.take(200), tr.why.take(300), tr.expect.take(200))
            }.filter { line ->
                run.needs.none { it.from == line.from && it.title == line.title }
            }
            if (newQuestions.isEmpty() && newNeeds.isEmpty()) return@update current
            current + (runId to run.copy(
                questions = run.questions + newQuestions,
                needs = run.needs + newNeeds,
            ))
        }
    }

    /**
     * [37099d] Soruların cevabını eşler.
     *
     * ## Neden eşleme, neden ayrı alan değil
     *
     * Protokolde "şu soruya şu cevap" diye bir alan yok: üye, soruyu aldığı
     * turda **pozisyon** yazar ve cevabı o metnin içinde verir. Ayrı bir cevap
     * alanı eklemek istem şemasını değiştirmek demekti (ve model onu
     * doldurmayabilirdi). Bunun yerine, hedef üyenin o turdan **sonraki**
     * pozisyon metni cevap kabul edilir.
     *
     * ## Neden yalnız bir kez
     *
     * Aynı soruya sonraki turlarda tekrar cevap yazılırsa ilk cevap korunur:
     * kullanıcının okuduğu satır sonradan değişirse hat "canlı" değil
     * "kayan" olur.
     *
     * ## Sınır (dürüst)
     *
     * Bu bir **yaklaşıklama**: üye cevabı başka bir konuya geçerek de
     * verebilir. Metin, eşleşmenin ne olduğunu söyler ("hedefin sonraki
     * pozisyonu"), böylece kullanıcı bunu kesin bir cevap sanmaz.
     */
    fun matchAnswers(runId: String, round: Int, member: String, claim: String) {
        if (claim.isBlank()) return
        _runs.update { current ->
            val run = current[runId] ?: return@update current
            var changed = false
            val updated = run.questions.map { q ->
                if (q.answer.isBlank() && q.answeredRound == 0 &&
                    q.to.equals(member, ignoreCase = true) && q.round < round
                ) {
                    changed = true
                    q.copy(answer = claim.take(400), answeredRound = round)
                } else {
                    q
                }
            }
            if (!changed) current else current + (runId to run.copy(questions = updated))
        }
    }

    fun claims(runId: String, consensus: Consensus, tokens: Int) {
        _runs.update { current ->
            val run = current[runId] ?: return@update current
            current + (runId to run.copy(
                claims = consensus.verdicts.map { v ->
                    ClaimLine(
                        id = v.claim.id,
                        text = v.claim.text,
                        support = v.claim.support,
                        oppose = v.claim.oppose,
                        uncertain = v.claim.uncertain,
                        verdict = v.verdict.name.lowercase(),
                        stances = v.claim.stances.mapValues { it.value.wire },
                    )
                },
                tokensUsed = tokens,
            ))
        }
    }

    fun finish(runId: String, outcome: CouncilOutcome) {
        _runs.update { current ->
            val run = current[runId] ?: return@update current
            current + (runId to run.copy(
                phase = Phase.DONE,
                round = outcome.rounds,
                tokensUsed = outcome.tokensUsed,
                dissent = outcome.dissent,
                claims = outcome.consensus.verdicts.map { v ->
                    ClaimLine(
                        id = v.claim.id,
                        text = v.claim.text,
                        support = v.claim.support,
                        oppose = v.claim.oppose,
                        uncertain = v.claim.uncertain,
                        verdict = v.verdict.name.lowercase(),
                        stances = v.claim.stances.mapValues { it.value.wire },
                    )
                },
                finished = Finished(
                    stoppedBy = outcome.stoppedBy.name.lowercase(),
                    verified = outcome.verified,
                    rounds = outcome.rounds,
                    tokensUsed = outcome.tokensUsed,
                ),
            ))
        }
    }

    /**
     * [37051] Add or update one task on a running run's board.
     *
     * Called from three places (a member's `task_update`, the run's hand-over
     * step, and a manual refresh) with the same effect, which is the point: the
     * screen has one source for "what is on the board right now" no matter which
     * path changed it.
     */
    fun taskAdded(runId: String, task: TeamTask) {
        _runs.update { current ->
            val run = current[runId] ?: return@update current
            current + (runId to run.copy(tasks = run.tasks + (task.id to task)))
        }
    }

    /** Tasks for a run, ordered open → doing → done → dropped, then oldest first. */
    fun tasksFor(runId: String): List<TeamTask> =
        _runs.value[runId]?.tasks?.values.orEmpty().sortedWith(
            compareBy({ it.state.ordinal }, { it.createdAtMs }),
        )

    /** Drop a run from memory. Called when its record is deleted or the app restarts. */
    fun forget(runId: String) {
        _runs.update { it - runId }
    }

    fun clearAll() {
        _runs.value = emptyMap()
    }

    /** Runs newest first, for the list screen. */
    fun sorted(): List<LiveRun> = _runs.value.values.sortedByDescending { it.startedAtMs }
}
