package com.openminis.app.agent.team

/**
 * [MINIS-OZEL 11.5 · 37037] The council's data model.
 *
 * ## Why these shapes
 *
 * A free-text "what do you think" round produces N essays that nobody can
 * compare, so convergence cannot be measured — it can only be asserted by a
 * model, and a model asserting "they agree" is exactly the failure this design
 * exists to avoid. Every structure below therefore exists to make ONE thing
 * countable:
 *
 *  - [Point] makes a member's argument an addressable item, so the same argument
 *    from two members can be the same claim.
 *  - [Claim] gives it a stable id (`C1`) that survives rounds, so "who changed
 *    their mind" is a diff of two maps rather than a reading task.
 *  - [Stance] is three values, not a score: agree / disagree / not sure. A
 *    number would invite averaging, and averaging "we should not ship this" with
 *    "we should" gives "ship it a bit".
 *  - [Position.falsifier] is mandatory because an unfalsifiable claim cannot
 *    lose an argument, so it would win every discussion by default.
 */

/** Where a point's authority comes from. `EVIDENCE` without a source is downgraded (see [Point.hasSource]). */
enum class Basis(val wire: String) {
    EVIDENCE("evidence"),
    REASONING("reasoning"),
    GUESS("guess"),
    ;

    companion object {
        fun parse(raw: String?): Basis =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: REASONING
    }
}

/**
 * A member's stance on one claim.
 *
 * [UNCERTAIN] is not a polite [OPPOSE]. It is load-bearing: half the team being
 * unsure about a claim is a different state from half opposing it, and the
 * stopping rule in [StanceMath] treats it separately. Collapsing the two is how
 * a council reports consensus it does not have.
 */
enum class Stance(val wire: String) {
    SUPPORT("support"),
    OPPOSE("oppose"),
    UNCERTAIN("uncertain"),
    ;

    companion object {
        fun parse(raw: String?): Stance =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: UNCERTAIN
    }
}

/** One addressable argument inside a member's position. */
data class Point(
    /** Member-local label (`p1`). Canonical ids (`C1`) come from the chair. */
    val ref: String,
    val text: String,
    val stance: Stance,
    val basis: Basis = Basis.REASONING,
    /** Evidence ids (`E3`) or raw URLs the member cited. */
    val evidence: List<String> = emptyList(),
) {
    /**
     * True when the point claims evidence and actually carries some. The parser
     * keeps a sourceless "evidence" point but marks it, because dropping it
     * would hide that the member tried to assert a fact it could not back, and
     * promoting it to `reasoning` would reward the attempt.
     */
    val hasSource: Boolean get() = basis != Basis.EVIDENCE || evidence.isNotEmpty()
}

/** A stated mind-change. Silent changes are the thing this record prevents. */
data class Change(
    val ref: String,
    val from: Stance,
    val to: Stance,
    val why: String,
) {
    /** A reversal with no stated reason is logged and flagged; see [Position.unstableReversals]. */
    val explained: Boolean get() = why.isNotBlank()
}

/** A question for another member, queued for the next round. */
data class Question(val to: String, val q: String)

/**
 * [MINIS-OZEL 11.5 · 37051] A task a member asks a colleague to pick up.
 *
 * This is the FREE path: it rides in the position JSON, so it costs no extra call
 * and blocks nobody. The live `assign_task` tool is the exception for the case
 * where a member cannot continue without the work being done — but a position
 * field is enough for "this should be looked at", which is most of what members
 * actually want to hand over.
 *
 * Kept distinct from [Question] on purpose: a question wants an ANSWER (one turn,
 * one reply), a task wants WORK (asynchronous, evidence, may outlive the run).
 * Collapsing them would make every hand-over either blocking or answerless.
 */
data class TaskRequest(
    val to: String,
    val title: String,
    val why: String = "",
    val expect: String = "",
)

/**
 * What a member returns for one round. This is the ONLY thing a member hands
 * back: prose in a report is welcome inside [claim] and [Point.text], but the
 * machine-readable part is what gets compared.
 */
data class Position(
    val member: String,
    val claim: String,
    val points: List<Point> = emptyList(),
    val changes: List<Change> = emptyList(),
    val questions: List<Question> = emptyList(),
    /** [37051] Work this member wants a colleague to pick up. */
    val tasks: List<TaskRequest> = emptyList(),
    val falsifier: String = "",
    /** True when the member produced nothing usable this round. Never fabricated. */
    val absent: Boolean = false,
    val absentWhy: String = "",
    /** Tokens reported by the provider, 0 when it said nothing. */
    val tokens: Int = 0,
    /** Which round this position was taken in. Stamped by [CouncilLoop]; 0 when unknown. */
    val round: Int = 0,
) {
    /** Reversals with no reason: the council must not count these as convergence. */
    val unstableReversals: List<Change> get() = changes.filter { !it.explained }

    val falsifiable: Boolean get() = falsifier.isNotBlank()
}

/**
 * A claim after the chair has canonicalised it: one id, one sentence, and every
 * member's stance attached.
 *
 * [stances] is keyed by member NAME, so a chair that emits the same member twice
 * cannot inflate a majority by writing two entries — the map collapses them.
 */
data class Claim(
    val id: String,
    val text: String,
    val stances: Map<String, Stance> = emptyMap(),
    val evidence: List<String> = emptyList(),
    val falsifiable: Boolean = true,
    /**
     * [37052] The chair's signal that this claim cannot be settled by argument.
     *
     * The chair is the only role that sees every position at once, so it is the
     * only one that can spot "these two are not disagreeing, they are missing the
     * same measurement". A claim marked here gets a task opened for it — otherwise
     * the next round spends five models' tokens re-arguing something that needs a
     * one-line lookup.
     */
    val needsTask: Boolean = false,
) {
    val support: Int get() = stances.values.count { it == Stance.SUPPORT }
    val oppose: Int get() = stances.values.count { it == Stance.OPPOSE }
    val uncertain: Int get() = stances.values.count { it == Stance.UNCERTAIN }
    val voted: Int get() = support + oppose + uncertain
}

/**
 * The canonical claim set for one round.
 *
 * [dropped] is not an error channel for the model's sloppiness — it is the
 * evidence that the chair lost something. A chair that silently merges four
 * arguments into one claim makes the council agree with itself; recording what
 * it could not place keeps that visible in the report.
 */
data class ClaimMatrix(
    val claims: List<Claim> = emptyList(),
    val dropped: List<String> = emptyList(),
) {
    fun first(id: String): Claim? = claims.firstOrNull { it.id.equals(id, ignoreCase = true) }
}

/** How one claim came out this round. */
enum class Verdict {
    /** Every participant on the same side, nobody unsure. */
    UNANIMOUS,

    /** One side reaches the majority threshold; the other side exists but is outvoted. */
    DECIDED,

    /** Both sides present and no side reaches the threshold. */
    CONTESTED,

    /** Too many "not sure" for the claim to mean anything yet. */
    UNRESOLVED,

    /** Nobody took a stance (chair invented the claim or lost every stance). */
    ORPHAN,
}

/** A claim together with its verdict. */
data class ClaimVerdict(val claim: Claim, val verdict: Verdict)

/**
 * One round's verdict on the whole matrix, plus the two facts that decide
 * whether the council continues.
 */
data class Consensus(
    val verdicts: List<ClaimVerdict> = emptyList(),
    /** Cell-level distance from the previous round; `-1` when there is none. */
    val cellsChanged: Int = -1,
    /** Consecutive rounds at or below the stability threshold, ending with this one. */
    val stableStreak: Int = 0,
    val attendees: Int = 0,
) {
    fun of(verdict: Verdict): List<ClaimVerdict> = verdicts.filter { it.verdict == verdict }

    val unanimous: List<ClaimVerdict> get() = of(Verdict.UNANIMOUS)
    val decided: List<ClaimVerdict> get() = of(Verdict.DECIDED)
    val contested: List<ClaimVerdict> get() = of(Verdict.CONTESTED)
    val unresolved: List<ClaimVerdict> get() = of(Verdict.UNRESOLVED)

    /** Full agreement: nothing contested, nothing unsure. */
    val converged: Boolean
        get() = verdicts.isNotEmpty() && contested.isEmpty() && unresolved.isEmpty() &&
            verdicts.none { it.verdict == Verdict.ORPHAN }

    /** Nothing left to argue about, but not everything is unanimous. */
    val settled: Boolean
        get() = verdicts.isNotEmpty() && contested.isEmpty() && unresolved.isEmpty()
}

/**
 * What the council is allowed to spend. Separate from [TeamBudget] because the
 * units differ: a handoff run is bounded by rounds, a council by rounds AND by
 * the tokens five models burn in one round.
 */
data class CouncilBudget(
    val maxRounds: Int = 4,
    val maxMinutes: Int = 12,
    /** Whole-run token ceiling. Stops mid-round when crossed; see [CouncilLoop]. */
    val maxTokens: Int = 250_000,
    /** Per member per round. A member that overshoots is still listened to, then stopped. */
    val maxMemberTokens: Int = 60_000,
    /** Rounds without progress before the council settles anyway. */
    val maxStableStreak: Int = 2,
    /** Cell changes at or below this count as "no movement". */
    val stabilityThreshold: Int = 1,
    /** Members answering at once. Set from device class by [CouncilPolicy]. */
    val waveSize: Int = 3,
    /** Chair attempts before a round is abandoned. */
    val maxChairRetries: Int = 2,
) {
    companion object {
        const val MAX_ROUNDS_CAP = 12
        const val MAX_MINUTES_CAP = 60
        const val MAX_WAVE = 5
    }
}

/** Why a run stopped. Recorded so the report cannot claim a clean finish it did not have. */
enum class CouncilStop {
    CONVERGED,
    SETTLED_STABLE,
    ROUNDS,
    TIME,
    TOKENS,
    NO_POSITIONS,
    CANCELLED,
}

/** One run's outcome. Every field here ends up in the report or the screen. */
data class CouncilOutcome(
    val answer: String,
    val verified: Boolean,
    val audit: CitationAudit.Result?,
    val consensus: Consensus,
    val rounds: Int,
    val stoppedBy: CouncilStop,
    val positions: List<Position>,
    val matrix: ClaimMatrix,
    val tokensUsed: Int,
    val events: List<CouncilEvent>,
    /** Claims that never got a majority, quoted from their opponents verbatim. */
    val dissent: List<String> = emptyList(),
    /**
     * [37051] Work the members handed to each other during the run.
     *
     * Returned rather than written: `CouncilLoop` is pure, so it cannot touch a
     * board. The runner persists these after the run, which also means a test can
     * assert the hand-over without a filesystem.
     */
    val requestedTasks: List<TaskRequest> = emptyList(),
)

/** Everything the screen draws, in order. */
sealed class CouncilEvent {
    data class Started(val task: String, val members: List<String>, val waveSize: Int) : CouncilEvent()
    data class RoundStarted(val round: Int, val questions: Int) : CouncilEvent()
    /**
     * [37099c] Bir üyenin tur sonu pozisyonu.
     *
     * [questions] ve [tasks] **eklendi** çünkü ikisi de modelde vardı ama
     * hiçbir yere yazılmıyordu: `Position.questions` (bir üyenin diğerine
     * sorduğu soru) ve `Position.tasks` ("bunu sen al" isteği). Sonuç,
     * ekranda "iletişim hattı" ve "ihtiyaçlar hattı" odalarının
     * gösterilecek verisi olmamasıydı — oda açılır, boş görünür, kullanıcı
     * "çalışmıyor" derdi. Varsayılanlar boş: mevcut olay üreticileri ve
     * testler değişmeden derlenir.
     */
    data class Positioned(
        val member: String,
        val claim: String,
        val absent: Boolean,
        val questions: List<Question> = emptyList(),
        val tasks: List<TaskRequest> = emptyList(),
        val round: Int = 0,
    ) : CouncilEvent()
    data class Canonicalized(val claims: Int, val dropped: Int) : CouncilEvent()
    data class ConsensusReached(val verdicts: List<ClaimVerdict>, val cellsChanged: Int) : CouncilEvent()
    data class StableRound(val streak: Int) : CouncilEvent()
    data class Synthesized(val chars: Int) : CouncilEvent()
    data class Audited(val passed: Boolean, val problems: List<String>) : CouncilEvent()
    data class Finished(val stoppedBy: CouncilStop, val verified: Boolean) : CouncilEvent()
}
