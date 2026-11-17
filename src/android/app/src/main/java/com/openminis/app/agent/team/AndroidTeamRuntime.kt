package com.openminis.app.agent.team

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.team.Team
import com.openminis.app.data.team.TeamMember
import com.openminis.app.data.team.TeamStore
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MINIS-OZEL 11.4 S2] The Android side of the two seams [TeamLoop] declares.
 *
 * ## Why the orchestrator is NOT a chat session
 *
 * The orchestrator talks in JSON, has no tools and must be cheap: it is called
 * at least once per round, twice on a good turn. Routing it through a full agent
 * session would give it a tool belt it must not use, put every ledger in a
 * transcript nobody reads, and inherit a system prompt written for a
 * conversation. So it goes straight to the provider — one request, no streaming,
 * no history beyond what the loop hands it.
 *
 * ## Why a member IS a chat session
 *
 * The opposite reasoning, and the reason this is worth the ceremony: a member
 * must use the real tool belt — shell, browser, files, MCP — and every one of
 * those is already wired into `ChatViewModel`'s loop, together with the sandbox,
 * the permission gates, the offload prompts, the loop detector and the
 * foreground service. Re-implementing a second mini agent loop for team members
 * would mean a second, less safe copy of all of it.
 *
 * A member therefore gets a hidden child session (`parentSessionId` set, so the
 * session list never shows it and a cascade delete takes it with the parent) and
 * is driven by [HeadlessChatRunner], which is the same path scheduled tasks use.
 * One session per member per run, kept across rounds: a member that has already
 * surveyed a repo should not re-read it on round four.
 */
class AndroidTeamRuntime(
    private val context: Context,
    private val team: Team,
    private val parentSessionId: String?,
) {

    private val app: MinisApp? = context.applicationContext as? MinisApp

    /** Resolved once per run; a member with no model falls back, never fails. */
    private val resolvedEntries = mutableMapOf<String, ModelEntry?>()

    /** member name → its child session, created on first use. */
    private val memberSessions = mutableMapOf<String, String>()

    // ── Orchestrator ───────────────────────────────────────────────────────

    fun orchestrator(): TeamOrchestrator = object : TeamOrchestrator {
        override suspend fun ask(prompt: String, json: Boolean): String = withContext(Dispatchers.IO) {
            val repository = app?.providerRepositoryOrNull
                ?: throw IllegalStateException("provider repository unavailable")
            val entry = resolveOrchestratorEntry()
                ?: throw IllegalStateException("no usable model for the orchestrator")
            val instance = repository.instance(entry.providerInstanceId)
                ?: throw IllegalStateException("orchestrator provider instance missing")
            val apiKey = repository.requestCredential(instance)
                ?: throw IllegalStateException("no credential for ${instance.label}")
            val provider = ProviderFactory.create(
                instance, apiKey, entry.model, context,
                sessionId = ProviderFactory.oneOffSessionId(), overrides = entry.overrides,
            )
            // No tools, no history, low temperature: a ledger is a structured
            // answer, not a creative one, and a "helpful" orchestrator that
            // starts narrating is exactly what the parser has to reject.
            val response = provider.sendMessage(
                messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                systemPrompt = if (json) {
                    "You are a precise planning component. You answer with a single JSON object " +
                        "and nothing else. No markdown fences, no explanations."
                } else {
                    null
                },
                maxTokens = ORCHESTRATOR_MAX_TOKENS,
                temperature = 0.0,
                thinkingLevel = ThinkingLevel.OFF,
            )
            response.text
        }
    }

    // ── Members ────────────────────────────────────────────────────────────

    fun worker(): TeamWorkerRunner = object : TeamWorkerRunner {
        override suspend fun run(member: TeamMember, instruction: String, round: Int): MemberResult {
            val repo = app?.chatRepositoryOrNull
                ?: return MemberResult("Team runs need the app to be fully started.", failed = true)
            val entry = resolveEntry(member) ?: run {
                // Falling back keeps the team usable when a model was removed
                // from Providers after the team was saved. The report says so,
                // rather than silently running the wrong model.
                return MemberResult(
                    "No usable model for \"${member.name}\": the pinned model is missing from " +
                        "Providers. Re-pin it in the team, or the run continues without it.",
                    failed = true,
                )
            }
            val childId = try {
                memberSessions.getOrPut(member.name) {
                    val session = withContext(Dispatchers.IO) {
                        repo.createSession(
                            modelId = entry.baseModel.id,
                            title = "${team.name} · ${member.name}",
                            parentSessionId = parentSessionId,
                        )
                    }
                    withContext(Dispatchers.IO) {
                        repo.dao.updateSource(session.id, SOURCE_TEAM)
                        repo.updateSessionBinding(
                            session.id,
                            """{"type":"entry","entryId":"${entry.id}"}""",
                            entry.baseModel.id,
                        )
                    }
                    session.id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.info(TAG, "member session create failed for ${member.name}: ${e.message}")
                return MemberResult("Could not open a working session for \"${member.name}\".", failed = true)
            }

            val peers = team.members.filter { it.name != member.name }
            val brief = TeamPrompts.member(member, peers, scopeNote = member.scope.ifBlank { null })
            val prompt = buildString {
                appendLine(brief)
                appendLine()
                appendLine("---")
                appendLine("INSTRUCTION (round $round):")
                appendLine(instruction)
            }

            return try {
                val result = HeadlessChatRunner.prompt(
                    context = context,
                    sessionId = childId,
                    text = prompt,
                    wait = true,
                    timeoutMs = MEMBER_TIMEOUT_MS,
                )
                val text = result.responseText?.trim().orEmpty()
                if (result.timedOut) {
                    MemberResult(
                        "Ran out of time. Partial output:\n${text.take(1500)}",
                        failed = true,
                    )
                } else if (text.isEmpty()) {
                    MemberResult("Finished with no report (status ${result.status}).", failed = true)
                } else {
                    MemberResult(text, artifacts = extractArtifacts(text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.info(TAG, "member ${member.name} failed: ${e.message}")
                MemberResult("Member failed: ${e.message}", failed = true)
            }
        }
    }

    // ── Model resolution ───────────────────────────────────────────────────

    /**
     * The orchestrator's model, in this order: the team's own pin, then the
     * parent session's model, then the user's default primary group. Never a
     * hard-coded model name — the app has no idea which providers a user has.
     */
    private fun resolveOrchestratorEntry(): ModelEntry? {
        val repository = app?.providerRepositoryOrNull ?: return null
        team.orchestratorProviderInstanceId?.let { instanceId ->
            team.orchestratorModelId?.let { modelId ->
                repository.entriesFor(instanceId)
                    .firstOrNull { it.baseModel.id == modelId }
                    ?.let { return it }
            }
        }
        val all = repository.allVisibleEntries()
        return all.firstOrNull() ?: return null
    }

    private fun resolveEntry(member: TeamMember): ModelEntry? {
        val repository = app?.providerRepositoryOrNull ?: return null
        val cacheKey = "${member.providerInstanceId}#${member.modelId}"
        if (resolvedEntries.containsKey(cacheKey)) return resolvedEntries[cacheKey]
        val resolved = run {
            val instanceId = member.providerInstanceId
            val modelId = member.modelId
            if (!instanceId.isNullOrBlank() && !modelId.isNullOrBlank()) {
                // Exact pin first; then the same model id on any other instance
                // (a provider swap keeps the team working).
                repository.entriesFor(instanceId).firstOrNull { it.baseModel.id == modelId }
                    ?: repository.allVisibleEntries().firstOrNull { it.baseModel.id == modelId }
            } else {
                null
            }
        } ?: repository.allVisibleEntries().firstOrNull()
        resolvedEntries[cacheKey] = resolved
        return resolved
    }

    /**
     * Lifts paths and URLs out of a member's report so the orchestrator can cite
     * them without re-reading the whole text every round. Deliberately shallow:
     * this is a citation aid, not extraction.
     */
    private fun extractArtifacts(text: String): List<String> =
        Regex("""(/[\w./-]{3,}|https?://[^\s)]+)""")
            .findAll(text)
            .map { it.value }
            .distinct()
            .take(MAX_ARTIFACTS)
            .toList()

    companion object {
        private const val TAG = "AndroidTeamRuntime"
        const val SOURCE_TEAM = "team"
        private const val ORCHESTRATOR_MAX_TOKENS = 2048
        private const val MEMBER_TIMEOUT_MS = 8 * 60 * 1000L
        private const val MAX_ARTIFACTS = 12
    }
}

/**
 * [MINIS-OZEL 11.4 S2] Entry point for the `team_run` tool.
 *
 * Deliberately thin: resolve the team by id or name, build the runtime, run the
 * loop, format the report. Everything interesting is in [TeamLoop] (what to do)
 * and [AndroidTeamRuntime] (how to do it on this device), so a change to either
 * leaves this file alone.
 */
object TeamRunner {

    data class Outcome(
        val ok: Boolean,
        val output: String,
        val teamOutcome: TeamOutcome?,
    )

    suspend fun run(
        context: Context,
        sessionId: String?,
        teamIdOrName: String,
        task: String,
        budget: TeamBudget = TeamBudget(),
    ): Outcome = withContext(Dispatchers.IO) {
        val team = TeamStore.list(context).firstOrNull {
            it.id == teamIdOrName || it.name.equals(teamIdOrName, ignoreCase = true)
        } ?: return@withContext Outcome(
            ok = false,
            output = buildString {
                append("No team called \"$teamIdOrName\".")
                val names = TeamStore.list(context).map { it.name }
                if (names.isEmpty()) {
                    append(" No teams are defined yet — create one from the Ekip screen.")
                } else {
                    append(" Available teams: ${names.joinToString(", ")}.")
                }
            },
            teamOutcome = null,
        )

        if (team.members.size < Team.MIN_MEMBERS) {
            return@withContext Outcome(
                ok = false,
                output = "Team \"${team.name}\" has ${team.members.size} member(s). " +
                    "A team needs at least ${Team.MIN_MEMBERS}; add members on the Ekip screen.",
                teamOutcome = null,
            )
        }

        val runtime = AndroidTeamRuntime(context, team, parentSessionId = sessionId)
        val loop = TeamLoop(
            orchestrator = runtime.orchestrator(),
            runner = runtime.worker(),
            budget = budget,
        )
        val outcome = try {
            loop.run(teamName = team.name, members = team.members, task = task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.info("TeamRunner", "run failed: ${e.message}")
            return@withContext Outcome(false, "Team run failed: ${e.message}", null)
        }
        Outcome(ok = outcome.satisfied && outcome.verified, output = format(outcome), teamOutcome = outcome)
    }

    /**
     * The report the main model reads. Kept short and structured: the caller is
     * a model that must decide what to do next, so the answer, the verdict and
     * the reason for stopping lead, and the per-round trail follows only as far
     * as it is useful.
     */
    fun format(outcome: TeamOutcome): String = buildString {
        appendLine(if (outcome.satisfied && outcome.verified) "STATUS: done" else "STATUS: not finished")
        outcome.stoppedBy?.let { appendLine("Stopped by: $it") }
        appendLine("Rounds: ${outcome.rounds} · re-plans: ${outcome.replans} · stall peak: ${outcome.stalls}")
        appendLine()
        appendLine("RESULT:")
        appendLine(outcome.answer.take(6000))
        val failed = outcome.events.filterIsInstance<TeamEvent.Verification>()
            .lastOrNull { !it.verified }
        if (failed != null && failed.missing.isNotEmpty()) {
            appendLine()
            appendLine("VERIFICATION GAPS:")
            failed.missing.forEach { appendLine("- $it") }
        }
        val trail = outcome.events.filterIsInstance<TeamEvent.Asked>().takeLast(6)
        if (trail.isNotEmpty()) {
            appendLine()
            appendLine("LAST STEPS:")
            trail.forEach { appendLine("- [${it.speaker}] ${it.instruction.take(160)}") }
        }
    }
}
