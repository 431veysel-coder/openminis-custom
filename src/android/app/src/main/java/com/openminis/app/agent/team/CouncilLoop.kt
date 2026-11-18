package com.openminis.app.agent.team

import com.openminis.app.data.team.TeamMember
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/** What a member's model call actually returned. Parsing happens in the loop, not here. */
data class RawReply(
    val text: String,
    val tokens: Int = 0,
    /** Non-empty when the call failed outright; the member is then `absent`, never `uncertain`. */
    val failure: String = "",
)

/** The two seams the council needs from the device. Both interfaces: testable with fakes. */
interface CouncilModel {
    /** One request. [json] asks for a JSON-shaped reply; [maxTokens] bounds the cost. */
    suspend fun ask(prompt: String, json: Boolean, maxTokens: Int): String
}

interface CouncilMemberRunner {
    /** Runs [member] on [prompt]. Must not throw: failures come back as [RawReply.failure]. */
    suspend fun run(member: TeamMember, prompt: String, round: Int): RawReply
}

/**
 * [MINIS-OZEL 11.5 · 37037] The council loop — independent positions in, argued
 * report out.
 *
 * ## The shape, in one paragraph
 *
 * Every member answers alone. The chair folds those answers into one claim list.
 * [StanceMath] counts whether that list is settled. If it is not, each member
 * gets the condensed board and one more turn, and the loop counts again. When
 * the board settles, or the budget runs out, one model writes the report, and a
 * non-model check verifies the citations. That is all. There is no "should we
 * keep going?" question put to a model anywhere in this file, because that
 * question is the one every multi-agent loop gets wrong: it is answered by
 * whoever is winning.
 *
 * ## What is deliberately NOT here
 *
 * - **No re-planning.** A council has no plan to rewrite; its structure is the
 *   protocol. The handoff loop's stall/re-plan machinery stays in [TeamLoop].
 * - **No per-member retry inside a round.** A member that fails is `absent` and
 *   the round proceeds. Retrying one member while four wait is how a 2-minute
 *   round becomes a 12-minute one.
 * - **No catch of [kotlinx.coroutines.CancellationException].** Cancellation
 *   propagates: the caller (the screen, or `team_run`) owns "stop and synthesise
 *   what we have", and swallowing it here would make the stop button unreliable.
 *
 * ## Budget accounting
 *
 * Tokens are summed from what providers report. When a provider reports nothing,
 * [estimateTokens] is used and the report says "tahmini" — a made-up precise
 * number is worse than an honest estimate, and the budget still has to work for
 * providers with no usage field.
 */
class CouncilLoop(
    private val model: CouncilModel,
    private val members: CouncilMemberRunner,
    private val budget: CouncilBudget = CouncilBudget(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Root for the report's path check; null in tests. */
    private val auditRoot: File? = null,
    /** Evidence already fetched by tools before the run (url → id map is built here). */
    private val initialEvidence: List<CitationAudit.Evidence> = emptyList(),
    /**
     * [37061] Called after every round's positions are in, before the chair.
     *
     * The loop deliberately knows nothing about browsers, shells or view models —
     * but something has to release the per-round resources, and the END of a
     * round is the only moment where nothing is mid-flight. Doing it here keeps
     * that knowledge out of a file that is otherwise pure control flow, and gives
     * 37062 (sub-agent budget) the same seam.
     */
    private val onRoundEnd: suspend (round: Int) -> Unit = {},
) {

    suspend fun run(
        teamName: String,
        memberList: List<TeamMember>,
        task: String,
        deliverable: String = "",
        constraints: String = "",
        successCriteria: String = "",
        language: String? = null,
        onEvent: (CouncilEvent) -> Unit = {},
    ): CouncilOutcome {
        val events = mutableListOf<CouncilEvent>()
        fun emit(e: CouncilEvent) {
            events += e
            onEvent(e)
        }

        val names = memberList.map { it.name }
        emit(CouncilEvent.Started(task, names, budget.waveSize))
        if (memberList.isEmpty()) {
            return CouncilOutcome(
                answer = "Konseyde üye yok.", verified = false, audit = null,
                consensus = Consensus(), rounds = 0, stoppedBy = CouncilStop.NO_POSITIONS,
                positions = emptyList(), matrix = ClaimMatrix(), tokensUsed = 0, events = events,
            )
        }

        val brief = CouncilPrompts.brief(task, deliverable, constraints, successCriteria, language)
        val evidence = initialEvidence.toMutableList()
        val usedEvidenceIds = mutableSetOf<String>().apply {
            addAll(initialEvidence.map { it.id })
        }

        val startedAt = clock()
        var tokens = 0
        var round = 0
        var streak = 0
        var previous: ClaimMatrix? = null
        var consensus = Consensus()
        var stop = CouncilStop.ROUNDS
        val allPositions = mutableListOf<Position>()
        var lastRoundPositions: List<Position> = emptyList()
        // [37051] Hand-overs, deduped: the same (to, title) asked for in round 2
        // and round 4 is one task, not two. A member restating a request is the
        // normal case, not a new one.
        val requestedTasks = LinkedHashMap<String, TaskRequest>()

        while (true) {
            if (round >= budget.maxRounds) {
                stop = CouncilStop.ROUNDS
                break
            }
            if (clock() - startedAt > budget.maxMinutes * 60_000L) {
                stop = CouncilStop.TIME
                break
            }
            if (tokens >= budget.maxTokens) {
                stop = CouncilStop.TOKENS
                break
            }
            round++

            // Questions asked in the previous round, addressed to each member.
            val incoming = allPositions
                .filter { it.round == round - 1 }
                .flatMap { p -> p.questions }
            val byTarget = incoming.groupBy { it.to.lowercase() }
            emit(CouncilEvent.RoundStarted(round, incoming.size))

            val positions = runRound(
                memberList = memberList,
                brief = brief,
                round = round,
                matrix = previous,
                allPositions = allPositions,
                evidence = evidence,
                questionsFor = { member ->
                    byTarget[member.name.lowercase()].orEmpty()
                },
                onPosition = { position ->
                    if (!position.absent) {
                        registerEvidence(position, round, evidence, usedEvidenceIds)
                    }
                    emit(CouncilEvent.Positioned(position.member, position.claim, position.absent))
                },
            )
            tokens += positions.sumOf { it.tokens }
            positions.forEach { p ->
                p.tasks.forEach { t ->
                    val key = "${t.to.lowercase()}|${t.title.lowercase().trim()}"
                    requestedTasks.putIfAbsent(key, t)
                }
            }
            allPositions += positions
            lastRoundPositions = positions

            // Nothing is in flight between rounds: every member has returned and
            // the chair has not started. Release per-round resources here.
            // `runCatching` would also swallow CancellationException, which would
            // make the stop button unreliable during cleanup — so the catch is
            // explicit and cancellation is rethrown.
            try {
                onRoundEnd(round)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cleanup failing must never abort a run that has answers in
                // hand, so this is intentionally not rethrown — but it IS logged,
                // because a cleanup that always fails is a leak nobody would
                // otherwise see.
                com.openminis.app.logging.AppLogger.info(
                    "CouncilLoop", "round $round cleanup failed: ${e.message}",
                )
            }

            val answered = positions.count { !it.absent }
            if (answered == 0) {
                stop = CouncilStop.NO_POSITIONS
                break
            }

            val matrix = chair(round, positions, previous) ?: previous ?: ClaimMatrix()
            emit(CouncilEvent.Canonicalized(matrix.claims.size, matrix.dropped.size))
            if (matrix.claims.isEmpty()) {
                stop = CouncilStop.NO_POSITIONS
                break
            }

            consensus = StanceMath.evaluate(
                matrix = matrix,
                previous = previous,
                attendees = answered,
                stableStreak = streak,
                budget = budget,
            )
            streak = consensus.stableStreak
            previous = matrix
            emit(CouncilEvent.ConsensusReached(consensus.verdicts, consensus.cellsChanged))
            if (streak > 0) emit(CouncilEvent.StableRound(streak))

            if (consensus.converged) {
                stop = CouncilStop.CONVERGED
                break
            }
            if (streak >= budget.maxStableStreak) {
                stop = CouncilStop.SETTLED_STABLE
                break
            }
            // Tokens were spent up to here; the next round's check at the top of
            // the loop is what actually stops it, so a round is never left
            // half-written on the board.
        }

        val matrix = previous ?: ClaimMatrix()
        val dissent = StanceMath.dissent(consensus, allPositions)

        // ── Synthesis ──────────────────────────────────────────────────────
        // Runs even for a stopped run: a council that spent tokens and produced
        // no report is the worst possible outcome for the user.
        var report = synthesize(brief, consensus, evidence, dissent, language, lastRoundPositions)
        emit(CouncilEvent.Synthesized(report.length))

        var audit = CitationAudit.audit(report, evidence, auditRoot)
        if (!audit.passed && report.isNotBlank()) {
            val repaired = runCatching {
                model.ask(
                    CouncilPrompts.repair(report, audit.problems),
                    json = false,
                    maxTokens = SYNTHESIS_MAX_TOKENS,
                )
            }.getOrNull()
            if (!repaired.isNullOrBlank()) {
                val second = CitationAudit.audit(repaired, evidence, auditRoot)
                // Keep the repair only when it actually fixed something; a
                // "repair" that introduces new unknown citations is discarded.
                if (second.problems.size < audit.problems.size) {
                    report = repaired
                    audit = second
                }
            }
        }
        emit(CouncilEvent.Audited(audit.passed, audit.problems))
        emit(CouncilEvent.Finished(stop, audit.passed))

        return CouncilOutcome(
            answer = report,
            verified = audit.passed,
            audit = audit,
            consensus = consensus,
            rounds = round,
            stoppedBy = stop,
            positions = allPositions,
            matrix = matrix,
            tokensUsed = tokens,
            events = events,
            dissent = dissent,
            requestedTasks = requestedTasks.values.toList(),
        )
    }

    // ── One round ──────────────────────────────────────────────────────────

    private suspend fun runRound(
        memberList: List<TeamMember>,
        brief: String,
        round: Int,
        matrix: ClaimMatrix?,
        allPositions: List<Position>,
        evidence: List<CitationAudit.Evidence>,
        questionsFor: (TeamMember) -> List<Question>,
        onPosition: (Position) -> Unit,
    ): List<Position> {
        val results = mutableListOf<Position>()
        // Computed ONCE per round, not once per member: the board cannot change
        // during a round (evidence is registered after the wave returns), and
        // rebuilding it five times is five times the string work for one answer.
        val digest = if (round == 1) {
            ""
        } else {
            CouncilPrompts.digest(
                matrix ?: ClaimMatrix(),
                allPositions.filter { it.round == round - 1 },
                evidence,
            )
        }
        // Waves, in a fixed order: see CouncilPolicy.waves for why not a
        // semaphore. Awaiting wave by wave keeps the peak memory bounded to
        // waveSize live members, which is the number the policy is sized on.
        for (wave in CouncilPolicy.waves(memberList.size, budget.waveSize)) {
            val slice = wave.map { memberList[it] }
            val positions = coroutineScope {
                slice.map { member ->
                    val prompt = if (round == 1) {
                        CouncilPrompts.openingRound(brief)
                    } else {
                        CouncilPrompts.nextRound(
                            brief = brief,
                            round = round,
                            ownPrevious = allPositions.lastOrNull { it.member == member.name },
                            digest = digest,
                            incomingQuestions = questionsFor(member),
                            // Live peer answers land in 37038 with `ask_teammate`;
                            // until then a member's questions come back as the
                            // other side's next-round position, which is the
                            // queue path and needs no second channel here.
                            addressedAnswers = emptyList(),
                        )
                    }
                    async {
                        val reply = runCatching {
                            members.run(member, prompt, round)
                        }.getOrElse { e ->
                            // A thrown runner is a broken member, not a broken
                            // run; the same rule the interface documents.
                            RawReply("", 0, e.message ?: "runner hatası")
                        }
                        val position = if (reply.failure.isNotBlank()) {
                            Position(member = member.name, claim = "", absent = true, absentWhy = reply.failure)
                        } else {
                            PositionParser.parse(reply.text, member.name)
                        }
                        val tokens = if (reply.tokens > 0) reply.tokens else estimateTokens(prompt, reply.text)
                        // Stamped here, once: every later stage reads the round
                        // from the position instead of threading a parallel list
                        // through the chair and the digest.
                        position.copy(tokens = tokens, round = round)
                    }
                }.awaitAll()
            }
            positions.forEach {
                results += it
                onPosition(it)
            }
        }
        return results
    }

    // ── Chair ──────────────────────────────────────────────────────────────

    private suspend fun chair(round: Int, positions: List<Position>, previous: ClaimMatrix?): ClaimMatrix? {
        val prompt = CouncilPrompts.chair(round, positions, previous?.claims?.map { it.id }.orEmpty())
        repeat(budget.maxChairRetries.coerceAtLeast(1)) {
            val raw = runCatching {
                model.ask(prompt, json = true, maxTokens = CHAIR_MAX_TOKENS)
            }.getOrNull()
            val parsed = ClaimMatrixParser.parse(raw, positions.map { it.member })
            if (parsed != null) return parsed
        }
        return null
    }

    // ── Synthesis ──────────────────────────────────────────────────────────

    private suspend fun synthesize(
        brief: String,
        consensus: Consensus,
        evidence: List<CitationAudit.Evidence>,
        dissent: List<String>,
        language: String?,
        lastPositions: List<Position>,
    ): String {
        if (consensus.verdicts.isEmpty()) {
            // No chair output at all: fall back to the members' own claims so
            // the user still gets the disagreement instead of an empty screen.
            return lastPositions.filter { !it.absent }.joinToString("\n\n") { p ->
                "${p.member}: ${p.claim}"
            }
        }
        val prompt = CouncilPrompts.synthesis(brief, consensus, evidence, dissent, language)
        val text = runCatching {
            model.ask(prompt, json = false, maxTokens = SYNTHESIS_MAX_TOKENS)
        }.getOrNull()
        if (!text.isNullOrBlank()) return text.trim()
        // The synthesiser failed; a deterministic report beats nothing, and it
        // must not pretend to be better than it is.
        return buildString {
            appendLine("SENTEZ ÜRETİLEMEDİ — ham sonuçlar:")
            consensus.verdicts.forEach { v ->
                appendLine("- [${v.verdict.name.lowercase()}] ${v.claim.text}")
            }
            dissent.forEach { appendLine("- itiraz: $it") }
        }
    }

    // ── Evidence board ─────────────────────────────────────────────────────

    /**
     * Adds the sources a member cited.
     *
     * Everything added here is `unverified = true`: it is a URL a model TYPED,
     * not a page a tool FETCHED. The runtime seeds tool-verified rows through
     * [initialEvidence]. The distinction is what [CitationAudit] reports, and it
     * is why a report cannot launder an invented source into a citation.
     */
    private fun registerEvidence(
        position: Position,
        round: Int,
        board: MutableList<CitationAudit.Evidence>,
        usedIds: MutableSet<String>,
    ) {
        for (point in position.points) {
            for (ref in point.evidence) {
                val trimmed = ref.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("E", ignoreCase = true) && trimmed.drop(1).all { it.isDigit() }) {
                    continue // already an id from the board
                }
                if (!trimmed.startsWith("http", ignoreCase = true)) continue
                if (board.any { it.url.equals(trimmed, ignoreCase = true) }) continue
                if (board.size >= MAX_EVIDENCE) return
                var n = board.size + 1
                while (usedIds.contains("E$n")) n++
                val id = "E$n"
                usedIds += id
                board += CitationAudit.Evidence(
                    id = id,
                    url = trimmed,
                    quote = point.text.take(300),
                    member = position.member,
                    round = round,
                    unverified = true,
                )
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Rough fallback when a provider reports no usage. Four characters per token
     * is the usual English rule of thumb; Turkish tokenises worse, so this
     * deliberately over-counts (3 per token) — an estimate that runs the budget
     * out early is recoverable, one that runs it out late is not.
     */
    private fun estimateTokens(prompt: String, reply: String): Int =
        ((prompt.length + reply.length) / 3) + 16

    companion object {
        const val CHAIR_MAX_TOKENS = 3000
        const val SYNTHESIS_MAX_TOKENS = 6000
        const val MAX_EVIDENCE = 120
    }
}
