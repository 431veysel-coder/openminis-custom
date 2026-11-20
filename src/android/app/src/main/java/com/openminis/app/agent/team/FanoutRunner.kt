package com.openminis.app.agent.team

import android.content.Context
import com.openminis.app.data.team.Team
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [37091] FANOUT — "Compare" mode, actually implemented.
 *
 * ## The bug this replaces
 *
 * `TeamEditorScreen` has offered a Compare mode since 11.4 whose description is
 * exact: *"Every member answers the same task independently and the answers are
 * shown side by side. No discussion."* `TeamRunner` only ever branched on
 * `TeamMode.COUNCIL`; everything else — HANDOFF **and FANOUT** — fell through to
 * `TeamLoop`, which is a sequential orchestrator: one member at a time, each
 * working on the previous one's output. So the user picked Compare, and got a
 * relay race. Nothing in the UI said so.
 *
 * That is the worst class of feature bug: the screen promises one shape, the
 * engine runs another, and both look fine in a transcript.
 *
 * ## What it does now
 *
 * Every member gets the SAME prompt, answers without seeing anyone else, and the
 * answers are returned labelled and in roster order. No orchestrator, no
 * synthesis, no verification step — because "compare" means the user does the
 * comparing.
 *
 * ## Why waves and not `async` for all members at once
 *
 * `SessionConcurrencyManager.MAX_CONCURRENT` is 6 process-wide and a member
 * holds a slot while it streams. Launching eight members in one `coroutineScope`
 * would queue the last two invisibly behind the first six with no bound on peak
 * memory. `CouncilPolicy.waves` already encodes the device-sized number, and
 * reusing it means Compare and Council cannot disagree about how many members a
 * phone can carry.
 *
 * ## Why a failure is reported, not hidden
 *
 * A member whose model is missing from Providers, or whose call failed, would
 * otherwise just be absent from the list — and an absent column reads as "this
 * model had nothing useful to say", which is a different claim from "this model
 * could not be reached". The labelled error keeps that distinction.
 */
object FanoutRunner {

    private const val TAG = "FanoutRunner"

    suspend fun run(
        context: Context,
        sessionId: String?,
        team: Team,
        task: String,
    ): TeamRunner.Outcome = withContext(Dispatchers.IO) {
        // Same orphan sweep as the council: a crash mid-run leaves member
        // workspaces behind and the next run is the cheapest place to collect
        // them.
        runCatching { TeamRunStore.sweepOrphanSpaces(context) }

        val policy = AndroidCouncilRuntime.policyFor(context, team.members.size)
        val runId = "run_" + UUID.randomUUID().toString().replace("-", "").take(8)
        val runtime = AndroidCouncilRuntime(context, team, sessionId, runId)
        runtime.subAgentBudget = AndroidCouncilRuntime.subAgentBudgetFor(context)
        runtime.openRunGate()
        CouncilLiveRegistry.start(
            runId = runId,
            teamId = team.id,
            teamName = team.name,
            task = task,
            memberNames = team.members.map { it.name to it.role },
            // One round: Compare has no second pass by definition.
            roundsPlanned = 1,
        )

        try {
            val runner = runtime.members()
            val prompt = prompt(task, team.language.orEmpty())
            val answers = mutableListOf<Answer>()

            for (wave in CouncilPolicy.waves(team.members.size, policy.budget.waveSize)) {
                val slice = wave.map { team.members[it] }
                // `coroutineScope` + `async` inside a WAVE only: the wave size is
                // what bounds concurrency, so awaiting wave by wave is the bound,
                // not a limitation of this code.
                val batch = coroutineScope {
                    slice.map { member ->
                        async {
                            CouncilLiveRegistry.memberPosition(
                                runId = runId,
                                memberName = member.name,
                                claim = "",
                                absent = false,
                                why = "answering",
                            )
                            val reply = runner.run(member, prompt, 1)
                            Answer(
                                name = member.name,
                                role = member.role,
                                text = reply.text,
                                failure = reply.failure,
                                tokens = reply.tokens,
                            )
                        }
                    }.awaitAll()
                }
                answers += batch
                // Same reason as the council: tabs belong to the run, not to a
                // wave, and a phone cannot hold one pool per member across waves.
                runtime.evictMemberTabs()
            }

            val body = render(team.name, task, answers)
            CouncilLiveRegistry.phase(runId, CouncilLiveRegistry.Phase.DONE)

            val outcome = TeamOutcome(
                answer = body,
                // Compare makes no claim about correctness: nobody verified
                // anything, so reporting `verified = false` is the truth. A team
                // run's report says the same thing in its own words.
                satisfied = answers.any { it.failure.isEmpty() && it.text.isNotBlank() },
                verified = false,
                rounds = 1,
                stalls = 0,
                replans = 0,
                events = emptyList(),
            )
            // `TeamRunStore.record` takes a `CouncilOutcome` — the run history was
            // built for the council's shape (claims, consensus, dissent). A Compare
            // run has none of those, and inventing an empty Consensus to satisfy
            // the type would put a "0 claims" council report in the run list.
            // The report itself is the deliverable here, and it is already in the
            // transcript; a separate history entry is a 37092 job, not a lie to
            // paper over now.
            AppLogger.info(TAG, "fanout $runId done: ${answers.count { it.failure.isEmpty() }}/${answers.size} answered")

            TeamRunner.Outcome(ok = outcome.satisfied, output = body, teamOutcome = outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.info(TAG, "fanout run failed: ${e.message}")
            TeamRunner.Outcome(false, "Compare run failed: ${e.message}", null)
        } finally {
            // One place, all three paths — same contract as CouncilRunner.
            runtime.closeRunGate()
            runtime.releaseHolds()
            AskTeammateQuota.forgetRun(runId)
        }
    }

    private data class Answer(
        val name: String,
        val role: String,
        val text: String,
        val failure: String,
        val tokens: Int,
    )

    /**
     * The one prompt every member gets.
     *
     * Deliberately NOT the council brief: that one asks for a position, a claim
     * and a falsifier, which is a debate format. Compare wants an answer, and a
     * member told to produce a structured position spends its turn on the format
     * instead of the task.
     */
    private fun prompt(task: String, language: String): String = buildString {
        appendLine("Answer this task on your own. You cannot see any other agent's answer.")
        appendLine()
        appendLine("TASK:")
        appendLine(task)
        appendLine()
        if (language.isNotBlank()) appendLine("Answer in: $language")
        appendLine("Be concrete and complete: the reader is comparing your answer with other")
        appendLine("models' answers to the same task and will judge them on content alone.")
    }

    private fun render(teamName: String, task: String, answers: List<Answer>): String = buildString {
        appendLine("# Compare — $teamName")
        appendLine()
        appendLine("**Task:** ${task.take(400)}")
        appendLine()
        appendLine("${answers.count { it.failure.isEmpty() }} of ${answers.size} members answered independently.")
        appendLine()
        answers.forEachIndexed { i, a ->
            appendLine("## ${i + 1}. ${a.name}${if (a.role.isNotBlank()) " · ${a.role}" else ""}")
            appendLine()
            if (a.failure.isNotEmpty()) {
                appendLine("_Could not answer: ${a.failure}_")
            } else if (a.text.isBlank()) {
                appendLine("_Empty answer._")
            } else {
                appendLine(a.text)
            }
            appendLine()
        }
    }
}
