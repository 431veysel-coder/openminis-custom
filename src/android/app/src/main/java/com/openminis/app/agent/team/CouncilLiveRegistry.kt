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
