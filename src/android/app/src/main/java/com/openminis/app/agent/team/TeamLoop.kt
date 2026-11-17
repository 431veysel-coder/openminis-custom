package com.openminis.app.agent.team

import com.openminis.app.data.team.TeamMember
import org.json.JSONObject

/** A run event, in the order it happened. This is what the panel renders. */
sealed class TeamEvent {
    data class Planned(val ledger: TaskLedger, val round: Int) : TeamEvent()
    data class Asked(val speaker: String, val instruction: String, val round: Int) : TeamEvent()
    data class Answered(val speaker: String, val result: String, val round: Int) : TeamEvent()
    data class Stalled(val stalls: Int, val why: String, val round: Int) : TeamEvent()
    data class Replanned(val round: Int) : TeamEvent()
    data class Verification(val verified: Boolean, val missing: List<String>) : TeamEvent()
    data class Finished(val answer: String, val rejected: Boolean) : TeamEvent()
}

/** What a member produced. [artifacts] are paths/URLs the orchestrator may pass on. */
data class MemberResult(
    val summary: String,
    val artifacts: List<String> = emptyList(),
    val failed: Boolean = false,
)

/** A completed run. */
data class TeamOutcome(
    val answer: String,
    val satisfied: Boolean,
    val verified: Boolean,
    val rounds: Int,
    val stalls: Int,
    val replans: Int,
    val events: List<TeamEvent>,
    /** Set when the loop stopped on a limit rather than on success. */
    val stoppedBy: StopReason? = null,
) {
    enum class StopReason { ROUNDS, TIME, STALLS, NO_PLAN, ABORTED }
}

/**
 * The two seams the loop needs. Both are interfaces so the loop can be tested
 * with fakes, and so the Android implementations (an orchestrator model call, a
 * child agent job) can be swapped without touching the control flow.
 */
interface TeamOrchestrator {
    /** One orchestrator request. [json] asks for a JSON-shaped reply. */
    suspend fun ask(prompt: String, json: Boolean): String
}

interface TeamWorkerRunner {
    /** Runs [member] on [instruction]; must not throw — failures come back as [MemberResult.failed]. */
    suspend fun run(member: TeamMember, instruction: String, round: Int): MemberResult
}

/**
 * [MINIS-OZEL 11.4 S2] The run loop: Magentic-One's outer/inner split, with a
 * mandatory verification step and a hard budget.
 *
 * The shape, in one paragraph: fill the task ledger → inner loop asks the
 * orchestrator for a progress ledger → if satisfied, VERIFY, and if verification
 * fails feed the gaps back and continue → if stalled three times, rewrite the
 * task ledger → otherwise hand the instruction to the named member and put its
 * report where the next ledger can see it. Nothing else. Every "smart" addition
 * tried in the reference implementations (dynamic speaker weighting, learned
 * topologies) buys accuracy at a token cost this app cannot pay on a phone.
 *
 * The loop never throws for an orchestrator's bad reply: an unusable ledger is
 * retried [TeamBudget.maxLedgerRetries] times and then treated as a stall, which
 * re-plans. Failing the whole run because a model wrote prose around its JSON
 * would make a cheap orchestrator unusable; failing to notice would make a
 * looping run unbounded.
 */
class TeamLoop(
    private val orchestrator: TeamOrchestrator,
    private val runner: TeamWorkerRunner,
    private val budget: TeamBudget = TeamBudget(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun run(
        teamName: String,
        members: List<TeamMember>,
        task: String,
        onEvent: (TeamEvent) -> Unit = {},
    ): TeamOutcome {
        val events = mutableListOf<TeamEvent>()
        fun emit(e: TeamEvent) {
            events += e
            onEvent(e)
        }

        if (members.isEmpty()) {
            val answer = "No members in this team, nothing was run."
            emit(TeamEvent.Finished(answer, rejected = true))
            return TeamOutcome(answer, false, false, 0, 0, 0, events, TeamOutcome.StopReason.NO_PLAN)
        }

        val startedAt = clock()
        val stallTracker = StallTracker(budget.maxStalls)
        val names = members.map { it.name }

        // ── Outer loop: the task ledger ───────────────────────────────────
        var ledger = planTask(teamName, members, task) ?: TaskLedger()
        emit(TeamEvent.Planned(ledger, 0))
        if (ledger.plan.isEmpty()) {
            // A plan is not optional: without one the inner loop has nothing to
            // execute and every turn would be a fresh guess.
            val answer = "The orchestrator produced no plan for this task."
            emit(TeamEvent.Finished(answer, rejected = true))
            return TeamOutcome(answer, false, false, 0, 0, 0, events, TeamOutcome.StopReason.NO_PLAN)
        }

        var lastInstruction: String? = null
        var lastResult: String? = null
        var round = 0
        var stalls = 0
        var replans = 0
        var pendingVerification: List<String> = emptyList()

        while (round < budget.maxRounds) {
            if (clock() - startedAt > budget.maxMinutes * 60_000L) {
                val answer = lastResult ?: "Time budget reached before the task was finished."
                emit(TeamEvent.Finished(answer, rejected = true))
                return TeamOutcome(
                    answer, false, false, round, stalls, replans, events, TeamOutcome.StopReason.TIME,
                )
            }
            round++

            val prompt = TeamPrompts.progressLedger(
                task = task,
                ledger = ledger,
                members = members,
                lastInstruction = lastInstruction,
                lastResult = lastResult,
            )

            var progress: ProgressLedger? = null
            for (attempt in 1..budget.maxLedgerRetries) {
                val raw = runCatching { orchestrator.ask(prompt, json = true) }.getOrNull()
                progress = ProgressLedgerParser.parse(raw, names)
                if (progress != null) break
                if (attempt == budget.maxLedgerRetries) break
            }

            if (progress == null) {
                // Unusable ledger == no progress. Count it as a stall so the run
                // re-plans instead of repeating the same failed request forever.
                stalls++
                emit(TeamEvent.Stalled(stalls, "unusable progress ledger", round))
                if (stalls >= budget.maxStalls) {
                    replans++
                    ledger = replan(teamName, members, task, ledger, lastResult) ?: ledger
                    emit(TeamEvent.Replanned(round))
                    stalls = 0
                }
                continue
            }

            if (progress.satisfied.answer) {
                val deliverable = lastResult.orEmpty()
                val verification = verify(task, deliverable)
                emit(TeamEvent.Verification(verification.verified, verification.missing))
                if (verification.verified) {
                    emit(TeamEvent.Finished(deliverable, rejected = false))
                    return TeamOutcome(
                        deliverable, true, true, round, stalls, replans, events,
                    )
                }
                // Verification failed: this is not a stall yet — the team gets a
                // targeted turn to close the named gaps (MAST FC3).
                pendingVerification = verification.missing
                lastInstruction = TeamPrompts.verificationFailure(verification.missing)
                lastResult = deliverable
                val next = progress.nextSpeaker.ifBlank {
                    ledger.plan.lastOrNull()?.assignee ?: names.first()
                }
                val member = members.firstOrNull { it.name.equals(next, ignoreCase = true) }
                    ?: members.first()
                val result = runner.run(member, lastInstruction.orEmpty(), round)
                lastResult = result.summary.ifBlank { "(no output)" }
                emit(TeamEvent.Answered(member.name, lastResult, round))
                continue
            }

            val replanNow = stallTracker.after(progress)
            stalls = maxOf(stalls, stallTracker.lastDecisionCount)
            if (replanNow) {
                emit(
                    TeamEvent.Stalled(
                        stallTracker.lastDecisionCount,
                        progress.progress.reason.ifBlank { progress.inLoop.reason },
                        round,
                    ),
                )
                replans++
                ledger = replan(teamName, members, task, ledger, lastResult) ?: ledger
                if (ledger.plan.isEmpty()) {
                    val answer = lastResult ?: "Re-planning produced no steps; stopping."
                    emit(TeamEvent.Finished(answer, rejected = true))
                    return TeamOutcome(
                        answer, false, false, round, budget.maxStalls, replans, events,
                        TeamOutcome.StopReason.NO_PLAN,
                    )
                }
                emit(TeamEvent.Replanned(round))
                lastInstruction = null
                continue
            }
            val instruction = progress.instruction
            val member = members.first { it.name.equals(progress.nextSpeaker, ignoreCase = true) }
            // Give the member the verification gaps when there are any: repeating
            // the original instruction after a failed check produces the same
            // answer that just failed it.
            val effective = if (pendingVerification.isEmpty()) {
                instruction
            } else {
                instruction + "\n\n" + TeamPrompts.verificationFailure(pendingVerification)
            }
            pendingVerification = emptyList()

            emit(TeamEvent.Asked(member.name, effective, round))
            val result = runner.run(member, effective, round)
            lastInstruction = effective
            lastResult = result.summary.ifBlank { "(no output)" }
            emit(TeamEvent.Answered(member.name, lastResult, round))
        }

        val answer = lastResult ?: "Round budget reached before the task was finished."
        emit(TeamEvent.Finished(answer, rejected = true))
        return TeamOutcome(
            answer, false, false, round, stalls, replans, events, TeamOutcome.StopReason.ROUNDS,
        )
    }

    // ── Outer loop ─────────────────────────────────────────────────────────

    private suspend fun planTask(teamName: String, members: List<TeamMember>, task: String): TaskLedger? {
        val prompt = TeamPrompts.taskLedger(teamName, members, task)
        repeat(2) {
            val raw = runCatching { orchestrator.ask(prompt, json = true) }.getOrNull()
            val parsed = TaskLedgerParser.parse(raw, members.map { it.name })
            if (parsed != null) return parsed
        }
        return null
    }

    private suspend fun replan(
        teamName: String,
        members: List<TeamMember>,
        task: String,
        previous: TaskLedger,
        lastResult: String?,
    ): TaskLedger? {
        val prompt = buildString {
            appendLine(TeamPrompts.taskLedger(teamName, members, task))
            appendLine()
            appendLine("The team is stuck. Previous plan:")
            previous.plan.forEach { appendLine("- [${it.assignee}] ${it.step}") }
            if (!lastResult.isNullOrBlank()) {
                appendLine("Last result: ${lastResult.take(2000)}")
            }
            appendLine()
            appendLine("Write a DIFFERENT plan that avoids what just failed. Keep known facts, correct wrong ones.")
        }
        repeat(2) {
            val raw = runCatching { orchestrator.ask(prompt, json = true) }.getOrNull()
            val parsed = TaskLedgerParser.parse(raw, members.map { it.name })
            if (parsed != null) return parsed
        }
        return null
    }

    private suspend fun verify(task: String, deliverable: String): Verification {
        if (deliverable.isBlank()) {
            return Verification(false, listOf("The team produced no deliverable."))
        }
        val raw = runCatching { orchestrator.ask(TeamPrompts.verification(task, deliverable), json = true) }
            .getOrNull()
        val json = ProgressLedgerParser.extractJsonObject(raw)
        val obj = json?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Verification(false, listOf("Verification answer was unreadable."))
        val verified = obj.optBoolean("verified", false)
        val missing = obj.optJSONArray("missing")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        return Verification(verified, missing)
    }

    data class Verification(val verified: Boolean, val missing: List<String>)
}

/**
 * Parses the task ledger. Tolerant of the same sloppiness as the progress
 * ledger, plus one extra rule: a step assigned to a name that is not a member is
 * DROPPED rather than renamed. Silently reassigning work to whoever is first is
 * how a research step ends up executed by the shell agent.
 */
object TaskLedgerParser {

    fun parse(raw: String?, participants: List<String>): TaskLedger? {
        val jsonText = ProgressLedgerParser.extractJsonObject(raw) ?: return null
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val plan = obj.optJSONArray("plan")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val step = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = step.optString("step").trim()
                if (text.isEmpty()) return@mapNotNull null
                val assignee = step.optString("assignee").trim()
                val member = participants.firstOrNull { it.equals(assignee, ignoreCase = true) }
                    ?: participants.firstOrNull { assignee.isNotEmpty() && assignee.contains(it, true) }
                    ?: return@mapNotNull null
                TaskLedger.PlanStep(step = text, assignee = member)
            }
        }.orEmpty()
        return TaskLedger(
            facts = strings(obj, "facts"),
            toLookUp = strings(obj, "to_look_up"),
            toDerive = strings(obj, "to_derive"),
            guesses = strings(obj, "guesses"),
            plan = plan.take(TaskLedger.maxSteps(participants.size)),
        )
    }

    private fun strings(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
    }
}
