package com.openminis.app.agent.team

import android.app.ActivityManager
import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.ContextSizeMeter
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.team.Team
import com.openminis.app.data.team.TeamMember
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [MINIS-OZEL 11.5 · 37037/37060] The Android side of the council's two seams.
 *
 * ## Split, and why each half is where it is
 *
 * The **chair and the synthesiser** go straight to a provider — one request, no
 * tools, no history, temperature 0. Same reasoning as the handoff orchestrator
 * (`AndroidTeamRuntime.orchestrator`): a JSON-shaped answer should not inherit a
 * tool belt or a system prompt written for a conversation.
 *
 * A **member** is a full agent and goes through [HeadlessChatRunner] into a real
 * child session, because the whole point (the user's requirement) is that a
 * member has a shell, a browser, files, MCP and its own sub agents. Re-implementing
 * a second agent loop for members would mean a second, less safe copy of the
 * sandbox, the permission gates, the loop detector and the foreground service.
 *
 * ## What makes a member a MEMBER and not a HELPER
 *
 * `memberConfig` is set on the child VM *before* its first turn (never
 * `helperConfig`). That single choice gives us three things at once, and each is
 * a deliberate inversion of the helper behaviour:
 *
 *  1. the belt becomes the member belt (`AgentTools` + `MemberToolBelt`);
 *  2. `holdsSlot` stays true, so the member takes one of the six global stream
 *     slots — which is what `CouncilPolicy`'s wave size is sized against;
 *  3. `fsSessionId`/`spaceId` point at the member's own workspace.
 */
class AndroidCouncilRuntime(
    private val context: Context,
    private val team: Team,
    private val parentSessionId: String?,
    private val runId: String,
) {

    private val app: MinisApp? = context.applicationContext as? MinisApp

    /** member name → its child session, kept across rounds so it remembers. */
    private val memberSessions = mutableMapOf<String, String>()

    /** member name → resolved model, resolved once per run. */
    private val resolvedEntries = mutableMapOf<String, ModelEntry?>()

    /**
     * One release handle per member session, held for the whole run.
     *
     * A member has no `AgentJob` entry, so `ChatViewModelStore.busyKeys` cannot
     * see it: between two rounds the member is idle by every signal the store
     * has, and the next session insert would evict it. The transcript would come
     * back empty on the following round and the member would re-explore work it
     * already did — the exact "it forgot what it found" failure this design is
     * trying to avoid.
     *
     * `holdWhileViewed` is the store's own refcounted pin (it exists for the
     * agent-transcript screen), so this reuses a mechanism that is already
     * load-bearing rather than adding a second notion of "busy".
     */
    private val holds = mutableListOf<() -> Unit>()

    /** Member names already pinned, so a re-stamp on round 4 does not pin twice. */
    private val pinned = mutableSetOf<String>()

    /**
     * [37062] The run's shared sub agent budget.
     *
     * Set by the caller before the first round; [RunSubAgentBudget.DISABLED]
     * until then so a member that somehow runs early is throttled rather than
     * unlimited.
     */
    var subAgentBudget: RunSubAgentBudget = RunSubAgentBudget.DISABLED

    /**
     * [37062] Register the run's sub agent gate and the members already known.
     *
     * Called once before the first round. Members' sessions do not exist yet, so
     * [registerMemberSession] is called again as each one is created — the gate
     * counts jobs by member session id, and a job cannot exist before its
     * session does.
     */
    fun openRunGate() {
        com.openminis.app.agent.jobs.AgentJobRegistry.registerRun(runId, subAgentBudget)
        memberSessions.values.forEach {
            com.openminis.app.agent.jobs.AgentJobRegistry.registerRunMember(runId, it)
        }
    }

    /** [37062] Release the gate and drop any delegation the run left queued. */
    fun closeRunGate() {
        com.openminis.app.agent.jobs.AgentJobRegistry.unregisterRun(runId)
    }

    /** [37062] Diagnostics for the run screen: active, launched, slots, ceiling. */
    fun gateSnapshot(): IntArray? =
        com.openminis.app.agent.jobs.AgentJobRegistry.runGateSnapshot(runId)

    /** Drops every pin. Safe to call twice; the handles are idempotent. */
    fun releaseHolds() {
        holds.forEach { runCatching { it() } }
        holds.clear()
        pinned.clear()
    }

    /**
     * [37061] Round-end cleanup: close the member's browser tabs, keep its shell.
     *
     * ## What is released and what is not, and why the split is here
     *
     * - **Tabs go.** A WebView is 40–90 MB and the member's next round almost
     *   never continues the same page — it opens what the new instruction needs.
     *   Holding five idle WebViews between rounds is what pushes the process into
     *   `onTrimMemory`, which evicts member VMs and produces the "member forgot
     *   its work" failure.
     * - **The shell stays.** It is the cheap part (20–40 MB, one process) and the
     *   expensive-in-time part: a member that has `pip install`ed something or
     *   surveyed a repo would otherwise start from scratch every round. It is
     *   still bounded: `ExecutionCoordinator` closes idle shells on its own timer.
     * - **The session and its transcript stay pinned** (see [holds]); only the
     *   tabs are dropped.
     *
     * Runs on Main because `BrowserTabPool.destroyAllTabs` releases WebViews and
     * is documented as main-thread-only.
     */
    suspend fun evictMemberTabs() = withContext(Dispatchers.Main) {
        for ((member, childId) in memberSessions) {
            val vm = com.openminis.app.debug.HeadlessChatRunner.existingViewModel(childId) ?: continue
            runCatching {
                if (vm.showBrowserSheet.value) return@runCatching // the user is looking at it
                vm.browserTabPool.destroyAllTabs()
                AppLogger.info(TAG, "round-end: closed tabs for $member")
            }.onFailure { AppLogger.info(TAG, "round-end tab cleanup failed for $member: ${it.message}") }
            // [37063] A takeover parked in REQUESTED across a round boundary would
            // wait for a command that has already finished — the grant signal
            // never arrives and the member's shell looks locked for the rest of
            // the run. A HELD takeover (USER) is left alone: the user is typing.
            runCatching {
                val cfg = vm.memberConfig ?: return@runCatching
                if (cfg.terminal.snapshot(agentBusy = false).state ==
                    com.openminis.app.agent.team.TerminalTakeover.State.REQUESTED
                ) {
                    cfg.terminal.onAgentCommandFinished()
                    AppLogger.info(TAG, "round-end: granted parked terminal takeover for $member")
                }
            }
        }
    }

    // ── Chair / synthesiser ────────────────────────────────────────────────

    fun model(): CouncilModel = object : CouncilModel {
        override suspend fun ask(prompt: String, json: Boolean, maxTokens: Int): String =
            withContext(Dispatchers.IO) {
                val repository = app?.providerRepositoryOrNull
                    ?: throw IllegalStateException("provider repository unavailable")
                val entry = resolveOrchestratorEntry()
                    ?: throw IllegalStateException("no usable model for the chair")
                val instance = repository.instance(entry.providerInstanceId)
                    ?: throw IllegalStateException("chair provider instance missing")
                val apiKey = repository.requestCredential(instance)
                    ?: throw IllegalStateException("no credential for ${instance.label}")
                val provider = ProviderFactory.create(
                    instance, apiKey, entry.model, context,
                    sessionId = ProviderFactory.oneOffSessionId(), overrides = entry.overrides,
                )
                val response = provider.sendMessage(
                    messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                    systemPrompt = if (json) {
                        "You are a precise structured-output component. You answer with a single " +
                            "JSON object and nothing else. No markdown fences, no explanation."
                    } else {
                        // null, not "": the provider then sends no system
                        // message at all, which is what we want for the
                        // synthesiser — it has the brief inline.
                        null
                    },
                    maxTokens = maxTokens,
                    // A ledger and a report are not creative writing. 0 also
                    // matters for the chair: a chatty chair that reorders and
                    // renames claims between rounds makes the stability measure
                    // report movement that no member made.
                    temperature = 0.0,
                    thinkingLevel = ThinkingLevel.OFF,
                )
                response.text
            }
    }

    // ── Members ────────────────────────────────────────────────────────────

    fun members(): CouncilMemberRunner = object : CouncilMemberRunner {
        override suspend fun run(member: TeamMember, prompt: String, round: Int): RawReply {
            val repo = app?.chatRepositoryOrNull
                ?: return RawReply("", failure = "the app is not fully started yet")
            val entry = resolveEntry(member)
                ?: return RawReply(
                    "",
                    failure = "no usable model for \"${member.name}\": its pinned model is missing " +
                        "from Providers, so it sat this round out",
                )

            val childId = try {
                memberSessions.getOrPut(member.name) {
                    val session = withContext(Dispatchers.IO) {
                        repo.createSession(
                            modelId = entry.baseModel.id,
                            title = "${team.name} · ${member.name}",
                            // A child row: hidden from the session list and taken
                            // with the parent on delete, exactly like a sub agent.
                            parentSessionId = parentSessionId,
                        )
                    }
                    withContext(Dispatchers.IO) {
                        repo.dao.updateSource(session.id, SOURCE_COUNCIL)
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
                return RawReply("", failure = "could not open a session for \"${member.name}\"")
            }

            // Belt, workspace identity and peer list, stamped before the first
            // turn. Idempotent: re-stamping on round 4 with the same values is a
            // no-op, and re-stamping with new values is what makes an edit to the
            // team take effect mid-run instead of needing a restart.
            stampMemberConfig(childId, member)
            // [37064] The run screen needs this map: it is the only way to open a
            // member's live session, and it exists nowhere else.
            CouncilLiveRegistry.attachSession(runId, member.name, childId)

            // Pin the session for the run, once per member. See [holds].
            if (pinned.add(member.name)) {
                holds += com.openminis.app.ui.chat.ChatViewModelStore.holdWhileViewed(childId)
                // [37062] The gate counts this member's sub agent jobs; it can
                // only do that once the session id exists.
                com.openminis.app.agent.jobs.AgentJobRegistry.registerRunMember(runId, childId)
            }

            // [37050] The member's own tasks, injected into the prompt rather
            // than left for it to fetch: a member that has to call task_list
            // first spends a turn discovering what it was already given. The
            // block is empty (and omitted) when the board has nothing for it, so
            // a council with no tasks costs no tokens for the idea.
            //
            // The board is read fresh per turn — it is a small JSON file and a
            // stale copy would hide a task assigned DURING this round.
            val taskBlock = runCatching {
                com.openminis.app.agent.team.TaskStore.load(context, team.id)
                    .promptBlock(member.name)
            }.getOrDefault("")
            val fullPrompt = if (taskBlock.isBlank()) prompt else prompt + "\n\n" + taskBlock

            return try {
                val result = awaitMemberTurn(childId, fullPrompt)
                val text = result.responseText?.trim().orEmpty()
                when {
                    // The watchdog's own status. Checked first: "Queued" must
                    // never be reported as a timeout, because the fix is
                    // completely different ("the council had no free slot") and a
                    // misleading reason sends the next round into the same wait.
                    result.status == "Queued" -> RawReply(
                        "",
                        failure = "waited ${QUEUE_PATIENCE_MS / 1000}s for a free stream slot and never " +
                            "started this round (the council had more members than slots). " +
                            "Its turn may still finish later.",
                    )
                    result.timedOut -> RawReply(
                        text,
                        tokens = estimate(text, prompt),
                        failure = "timed out after ${MEMBER_TIMEOUT_MS / 1000}s" +
                            if (text.isNotEmpty()) " (partial output kept)" else "",
                    )
                    text.isEmpty() -> RawReply(
                        "",
                        failure = "finished with no report (status ${result.status})",
                    )
                    else -> RawReply(text, tokens = estimate(text, prompt))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.info(TAG, "member ${member.name} failed: ${e.message}")
                RawReply("", failure = "member failed: ${e.message}")
            }
        }
    }

    /**
     * [37062] Wait for one member turn, with a queue watchdog.
     *
     * ## Why a watchdog and not just a timeout
     *
     * `HeadlessChatRunner.prompt(timeoutMs = …)` counts the time the member spends
     * WAITING FOR A STREAM SLOT as part of its budget. `SessionConcurrencyManager`
     * queues a seventh session with **no timeout at all**, so a member can burn its
     * whole eight minutes before its first token and come back saying "timed out" —
     * a message that hides the real cause ("the council had no free slot") and
     * sends the next round into the same wait.
     *
     * This watches `suspendedSessions`, which the manager already publishes and
     * which nothing in the app reads. A member queued for [QUEUE_PATIENCE_MS]
     * comes back `absent` with a reason that a human can act on, and the round
     * proceeds with the members that did get through.
     *
     * ## The honest cost
     *
     * Cancelling the wait does NOT cancel the member's turn: the turn runs in the
     * member view model's own scope, so it finishes later and its reply is written
     * into a transcript this round no longer reads. One wasted turn is the price
     * of not stalling the whole council behind a slot; the alternative — every
     * member waiting the full budget — wastes five.
     */
    private suspend fun awaitMemberTurn(childId: String, prompt: String): HeadlessChatRunner.PromptResult =
        coroutineScope {
            val deferred = async(Dispatchers.IO) {
                HeadlessChatRunner.prompt(
                    context = context,
                    sessionId = childId,
                    text = prompt,
                    wait = true,
                    timeoutMs = MEMBER_TIMEOUT_MS,
                )
            }
            var queuedSince = 0L
            while (!deferred.isCompleted) {
                delay(QUEUE_POLL_MS)
                if (deferred.isCompleted) break
                if (com.openminis.app.service.SessionConcurrencyManager.isSuspended(childId)) {
                    val now = System.currentTimeMillis()
                    if (queuedSince == 0L) queuedSince = now
                    if (now - queuedSince >= QUEUE_PATIENCE_MS) {
                        deferred.cancel()
                        AppLogger.info(TAG, "member ${childId.take(14)} waited $QUEUE_PATIENCE_MS ms for a slot — absent")
                        return@coroutineScope HeadlessChatRunner.PromptResult(
                            status = "Queued",
                            responseText = null,
                            timedOut = true,
                        )
                    }
                } else {
                    queuedSince = 0L
                }
            }
            deferred.await()
        }

    /**
     * Stamps the member marker on the child VM, creating it if needed.
     *
     * ## Why this creates the VM instead of reading it
     *
     * `existingViewModel` returns null before the first prompt has built one, and
     * a null here means `memberConfig` never gets set — the member would then run
     * with the TOP belt, which includes `team_run`. So the VM is built first,
     * stamped, and only then prompted; [HeadlessChatRunner.prompt] finds the same
     * instance through its cache and uses it.
     *
     * ## The session key
     *
     * `sp_<runId8>_<slug>`: stable for the whole run (the member keeps its
     * workspace and shell between rounds) and unique per run (two runs of the
     * same team never share a directory, a shell or a tab set).
     *
     * ## PoolKind.CHILD, deliberately
     *
     * A member's session is hidden from the session list and dies with its
     * parent, which is exactly what the CHILD pool means, and it keeps the
     * eviction accounting at one number instead of two.
     *
     * ## Why the cap did not need raising
     *
     * `ChatViewModelStore.MAX_CACHED_CHILD` is 6 (a SOFT cap: a store with work in
     * flight, or one held by `holdWhileViewed`, is never evicted), so five pinned
     * members push the pool over rather than through it. An earlier plan raised it
     * to 10 for this run; that turned out to be unnecessary once the pin was in
     * place, and raising a soft cap to accommodate entries that are already exempt
     * from eviction would only have hidden the real relationship. Members are
     * protected by the pin, not by the cap.
     */
    private suspend fun stampMemberConfig(childId: String, member: TeamMember) {
        withContext(Dispatchers.Main) {
            val vm = com.openminis.app.debug.HeadlessChatRunner.viewModelFor(
                context, childId, com.openminis.app.ui.chat.ChatViewModelStore.PoolKind.CHILD,
            )
            vm.memberConfig = MemberConfig(
                runId = runId,
                teamId = team.id,
                memberName = member.name,
                role = member.role,
                tools = member.tools,
                peers = team.members.map { it.name }.filter { it != member.name },
                sessionKey = spaceKeyFor(member.name),
            ).also { cfg ->
                // [37063] Carried forward, not recreated: this method runs once
                // per round, and a takeover the user holds must survive the round
                // boundary. Recreating it would hand the shell back to the agent
                // the moment a new round started.
                // Assigned after construction because `terminal` sits outside the
                // primary constructor — that placement is what keeps it out of
                // `equals`, so a re-stamped config does not look different.
                cfg.terminal = vm.memberConfig?.terminal ?: TerminalTakeover()
            }
        }
    }

    fun spaceKeyFor(memberName: String): String {
        val slug = memberName.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(16)
            .ifBlank { "member" }
        return "sp_${runId.take(8)}_$slug"
    }

    // ── Model resolution ───────────────────────────────────────────────────

    /**
     * The chair's model: the team's own pin, then the parent session's model,
     * then any visible entry. Never a hard-coded name — the app cannot know which
     * providers a user has configured.
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
        return repository.allVisibleEntries().firstOrNull()
    }

    private fun resolveEntry(member: TeamMember): ModelEntry? {
        val repository = app?.providerRepositoryOrNull ?: return null
        val cacheKey = "${member.providerInstanceId}#${member.modelId}"
        if (resolvedEntries.containsKey(cacheKey)) return resolvedEntries[cacheKey]
        val resolved = run {
            val instanceId = member.providerInstanceId
            val modelId = member.modelId
            if (!instanceId.isNullOrBlank() && !modelId.isNullOrBlank()) {
                // Exact pin, then the same model id on another instance (a
                // provider swap keeps the team running).
                repository.entriesFor(instanceId).firstOrNull { it.baseModel.id == modelId }
                    ?: repository.allVisibleEntries().firstOrNull { it.baseModel.id == modelId }
            } else {
                // A member with no model pin is a deliberate "same as the chair":
                // better than inventing a model the user does not have.
                resolveOrchestratorEntry()
            }
        }
        resolvedEntries[cacheKey] = resolved
        return resolved
    }

    // ── Tokens ─────────────────────────────────────────────────────────────

    /**
     * Token estimate for a member turn.
     *
     * [HeadlessChatRunner.PromptResult] carries no usage (`:676`), so this is an
     * ESTIMATE and the report says so. [ContextSizeMeter.estimateTokens] is used
     * rather than a chars/4 rule because it is fitted per character class and
     * Turkish tokenises worse than English — a flat rule under-counts exactly the
     * text this app produces most.
     */
    private fun estimate(reply: String, prompt: String): Int =
        ContextSizeMeter.estimateTokens(prompt) + ContextSizeMeter.estimateTokens(reply)

    companion object {
        private const val TAG = "AndroidCouncilRuntime"
        const val SOURCE_COUNCIL = "council"
        private const val MEMBER_TIMEOUT_MS = 8 * 60 * 1000L

        /** How often the queue watchdog looks. */
        private const val QUEUE_POLL_MS = 2_000L

        /** A member queued this long is reported absent instead of silently eating its budget. */
        private const val QUEUE_PATIENCE_MS = 90_000L

        /**
         * [37065] The model behind a peer name, resolved WITHOUT a runtime.
         *
         * The asker is inside its own turn and has no handle on the runtime, so
         * this goes through the team record and the provider repository directly.
         * The rules are the same three the member resolver uses (exact pin, same
         * model id elsewhere, the chair's model) — a peer that answers from a
         * different model than the one it debates with would make its answers
         * unusable as evidence.
         */
        fun peerEntryFor(
            context: Context,
            member: com.openminis.app.agent.team.MemberConfig,
            peerName: String,
        ): ModelEntry? {
            val app = context.applicationContext as? MinisApp ?: return null
            val repository = app.providerRepositoryOrNull ?: return null
            val team = com.openminis.app.data.team.TeamStore.get(context, member.teamId) ?: return null
            val peer = team.members.firstOrNull { it.name.equals(peerName, ignoreCase = true) }
                ?: return null
            val instanceId = peer.providerInstanceId
            val modelId = peer.modelId
            if (!instanceId.isNullOrBlank() && !modelId.isNullOrBlank()) {
                repository.entriesFor(instanceId).firstOrNull { it.baseModel.id == modelId }
                    ?.let { return it }
                repository.allVisibleEntries().firstOrNull { it.baseModel.id == modelId }
                    ?.let { return it }
            }
            // No pin: the team's chair model, the same fallback the member path
            // uses. Better than answering from an unrelated default.
            team.orchestratorProviderInstanceId?.let { iid ->
                team.orchestratorModelId?.let { mid ->
                    repository.entriesFor(iid).firstOrNull { it.baseModel.id == mid }
                        ?.let { return it }
                }
            }
            return repository.allVisibleEntries().firstOrNull()
        }

        /** Device → council depth. See [CouncilPolicy] for the numbers. */
        fun policyFor(context: Context, memberCount: Int? = null): CouncilPolicy.Preset {
            val (lowRam, totalMb) = deviceClass(context)
            return CouncilPolicy.forDevice(lowRam, totalMb, memberCount)
        }

        /** Device → run-level sub agent budget. See [RunSubAgentBudget]. */
        fun subAgentBudgetFor(context: Context): RunSubAgentBudget {
            val (lowRam, totalMb) = deviceClass(context)
            return RunSubAgentBudget.forDevice(lowRam, totalMb)
        }

        /** RAM class, measured once per call. Never throws: a missing service
         *  reads as a mid-range device, which is the safe middle. */
        private fun deviceClass(context: Context): Pair<Boolean, Int> {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            runCatching { am?.getMemoryInfo(info) }
            val totalMb = (info.totalMem / (1024L * 1024L)).toInt()
            return (am?.isLowRamDevice ?: false) to totalMb
        }
    }
}

/**
 * [MINIS-OZEL 11.5 · 37037] Entry point for a council run.
 *
 * Thin on purpose, mirroring [TeamRunner]: resolve the budget from the device,
 * build the runtime, run the loop, format the report. Everything interesting is
 * in [CouncilLoop] (what to do) and [AndroidCouncilRuntime] (how, on Android).
 */
object CouncilRunner {

    suspend fun run(
        context: Context,
        sessionId: String?,
        team: Team,
        task: String,
        budget: CouncilBudget? = null,
        deliverable: String = "",
        constraints: String = "",
        successCriteria: String = "",
        onEvent: (CouncilEvent) -> Unit = {},
    ): TeamRunner.Outcome = withContext(Dispatchers.IO) {
        // [37061] Cheap, once per run: member workspaces whose record is gone.
        // Doing it here rather than on a timer means a crash mid-run leaves the
        // directory for the NEXT run to clean up, and no background job owns it.
        runCatching { TeamRunStore.sweepOrphanSpaces(context) }

        val policy = AndroidCouncilRuntime.policyFor(context, team.members.size)
        val effective = budget ?: policy.budget
        val runId = "run_" + UUID.randomUUID().toString().replace("-", "").take(8)
        val runtime = AndroidCouncilRuntime(context, team, sessionId, runId)
        // [37062] The run's shared sub agent budget, sized by device class, and
        // the gate that enforces it across ALL members. Opened before the first
        // round so a member's very first delegation already sees it.
        runtime.subAgentBudget = AndroidCouncilRuntime.subAgentBudgetFor(context)
        runtime.openRunGate()
        // [37065] A fresh per-round allowance for `ask_teammate`. Set here as well
        // as per round so a run that never reaches round 2 still starts clean.
        com.openminis.app.agent.team.AskTeammateQuota.resetRound(runId)
        CouncilLiveRegistry.start(
            runId = runId,
            teamId = team.id,
            teamName = team.name,
            task = task,
            memberNames = team.members.map { it.name to it.role },
            roundsPlanned = policy.budget.maxRounds,
            waveSize = policy.budget.waveSize,
        )

        // Evidence the tools already fetched is not available before the run
        // starts (members fetch during their turns), so the board starts empty;
        // `registerEvidence` in the loop fills it from the members' own citations
        // and marks every one of them `unverified` until a tool-verified path
        // exists (37041).
        val loop = CouncilLoop(
            model = runtime.model(),
            members = runtime.members(),
            budget = effective,
            auditRoot = null,
            initialEvidence = emptyList(),
            // Round-end cleanup. It is a suspend hook (not a callback with a
            // blocking bridge): the loop already runs inside the run's coroutine,
            // so closing tabs must suspend there rather than block a dispatcher
            // thread with `runBlocking`.
            onRoundEnd = {
                runtime.evictMemberTabs()
                // [37065] A new round is a fresh question allowance. Done HERE
                // because this is the only place that knows a round ended without
                // a timer guessing at boundaries.
                com.openminis.app.agent.team.AskTeammateQuota.resetRound(runId)
            },
        )

        val outcome = try {
            loop.run(
                teamName = team.name,
                memberList = team.members,
                task = task,
                deliverable = deliverable,
                constraints = constraints,
                successCriteria = successCriteria,
                language = team.language,
                onEvent = { e ->
                    // Two consumers, one source: the caller's callback (a screen
                    // that is showing this run) and the process-level registry
                    // (any screen that opens later). Neither can be the only
                    // one — the first is gone when the user navigates away, and
                    // the second never sees a run it did not start.
                    runCatching {
                        when (e) {
                            is CouncilEvent.Started -> CouncilLiveRegistry.phase(runId, CouncilLiveRegistry.Phase.STARTING)
                            is CouncilEvent.RoundStarted ->
                                CouncilLiveRegistry.phase(runId, CouncilLiveRegistry.Phase.POSITIONS, e.round)
                            is CouncilEvent.Positioned -> {
                                CouncilLiveRegistry.memberPosition(runId, e.member, e.claim, e.absent, "")
                                // [37099c] Sorular ve devredilen işler: modelde
                                // vardılar, hiçbir yüzeye yazılmıyorlardı.
                                CouncilLiveRegistry.memberAsks(
                                    runId = runId,
                                    round = e.round,
                                    from = e.member,
                                    questions = e.questions,
                                    tasks = e.tasks,
                                )
                                // [37099d] Gelen pozisyon, ona sorulmuş soruların
                                // cevabı sayılır (hedefin sonraki turu). Sıra
                                // önemli: memberAsks önce, çünkü aynı turda
                                // sorulan soru kendi cevabı olamaz (q.round < round).
                                CouncilLiveRegistry.matchAnswers(
                                    runId = runId,
                                    round = e.round,
                                    member = e.member,
                                    claim = e.claim,
                                )
                            }
                            is CouncilEvent.Canonicalized ->
                                CouncilLiveRegistry.phase(runId, CouncilLiveRegistry.Phase.CANONICALIZING)
                            is CouncilEvent.ConsensusReached ->
                                CouncilLiveRegistry.claims(runId, Consensus(e.verdicts, e.cellsChanged), 0)
                            is CouncilEvent.Synthesized ->
                                CouncilLiveRegistry.phase(runId, CouncilLiveRegistry.Phase.SYNTHESIZING)
                            is CouncilEvent.Audited ->
                                CouncilLiveRegistry.phase(runId, CouncilLiveRegistry.Phase.VERIFYING)
                            is CouncilEvent.Finished ->
                                CouncilLiveRegistry.phase(runId, CouncilLiveRegistry.Phase.DONE)
                            else -> Unit
                        }
                    }
                    onEvent(e)
                },
            )
        } catch (e: CancellationException) {
            // Cancel is a legitimate ending (the user pressed stop). The
            // `finally` below unpins either way; a pinned session that is never
            // released keeps its member VM alive for the life of the process.
            throw e
        } catch (e: Exception) {
            AppLogger.info("CouncilRunner", "run failed: ${e.message}")
            return@withContext TeamRunner.Outcome(
                ok = false,
                output = "Council run failed: ${e.message}",
                teamOutcome = null,
            )
        } finally {
            // One place, all three paths (normal, cancelled, failed). Nothing
            // below this point needs the member sessions alive — the report is
            // already written into `outcome`.
            //
            // [37062] The gate closes FIRST: it also drops any delegation the
            // members left queued, and a queued sub agent that fires after the
            // run ends would spend tokens into a session nobody reads.
            runtime.closeRunGate()
            runtime.releaseHolds()
            // [37065] Question counters are per run; keeping them would leak one
            // entry per run for the life of the process.
            com.openminis.app.agent.team.AskTeammateQuota.forgetRun(runId)
        }

        // [37051] Turn the members' hand-overs into real board tasks.
        //
        // Done here, after the loop, rather than inside it: `CouncilLoop` is pure
        // and a test asserts the hand-over without touching a filesystem. Failures
        // are logged and swallowed — a run that produced an answer must not be
        // reported as failed because a board write went wrong.
        runCatching {
            var board = com.openminis.app.agent.team.TaskStore.load(context, team.id)
            outcome.requestedTasks.forEach { request ->
                // The recipient must be a real member; a task for someone who left
                // the roster is dropped with its reason kept in the title, rather
                // than assigned to nobody and forgotten.
                val known = team.members.any { it.name.equals(request.to, ignoreCase = true) }
                val task = TeamTask(
                    id = com.openminis.app.agent.team.TaskStore.newId(),
                    title = request.title,
                    detail = listOf(request.why, request.expect)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    owner = if (known) request.to else "",
                    runId = runId,
                    createdBy = "council",
                )
                val (next, stored, refusal) = board.add(task)
                if (refusal.isEmpty() && stored != null) board = next
            }
            // [37052] Claims the chair said cannot be settled by argument get a
            // task, with the owner chosen by ROLE rather than by round-robin: a
            // researcher for a lookup, an engineer for a measurement. Assigning an
            // unmeasurable claim to whoever spoke last is how a council spends
            // three rounds asking a critic to run a benchmark.
            outcome.matrix.claims.filter { it.needsTask }.forEach { claim ->
                val owner = ownerForClaim(team, claim)
                val task = TeamTask(
                    id = com.openminis.app.agent.team.TaskStore.newId(),
                    title = "Settle ${claim.id}: ${claim.text}".take(TaskBoard.MAX_TITLE),
                    detail = "The council could not settle this claim by argument " +
                        "(${claim.support} for, ${claim.oppose} against, ${claim.uncertain} unsure). " +
                        "Bring back a measurement, a file check or a source.",
                    owner = owner,
                    runId = runId,
                    fromClaim = claim.id,
                    createdBy = "chair",
                )
                val (next, stored, refusal) = board.add(task)
                if (refusal.isEmpty() && stored != null) board = next
            }
            com.openminis.app.agent.team.TaskStore.save(context, team.id, board)
            board.tasks.forEach { CouncilLiveRegistry.taskAdded(runId, it) }
        }.onFailure { AppLogger.info("CouncilRunner", "task board write failed: ${it.message}") }

        // [37064] The run screen's final state. Published before the record is
        // written, so a screen that is open updates even if the disk write fails.
        runCatching { CouncilLiveRegistry.finish(runId, outcome) }

        // Recording is best-effort and must never turn a finished run into a
        // failure: the answer is already in hand at this point.
        runCatching { TeamRunStore.record(context, runId, team, outcome, policy) }
            .onFailure { AppLogger.info("CouncilRunner", "run record not saved: ${it.message}") }

        TeamRunner.Outcome(
            ok = outcome.verified,
            output = format(outcome, policy, runId),
            teamOutcome = null,
        )
    }

    /**
     * [37052] Who should settle a claim the chair flagged.
     *
     * Role first, name second: the rule must be explainable ("the researcher got
     * it because it is a lookup") and stable, so two runs of the same council
     * assign the same kind of work to the same kind of member. Falls back to the
     * first member so a task is never left unowned by accident — an unowned task
     * is one nobody reads.
     */
    private fun ownerForClaim(team: com.openminis.app.data.team.Team, claim: Claim): String {
        fun pick(role: String): String? = team.members.firstOrNull {
            MemberToolBelt.rolePreset(it.role) != null &&
                it.role.trim().lowercase().startsWith(role)
        }?.name
        val text = claim.text.lowercase()
        val wantsRun = listOf("test", "benchmark", "run", "build", "measure", "ölç", "çalıştır")
            .any { text.contains(it) }
        val wantsLookup = listOf("source", "version", "documentation", "docs", "api", "kaynak")
            .any { text.contains(it) }
        return when {
            wantsRun -> pick("engineer") ?: pick("researcher")
            wantsLookup -> pick("researcher") ?: pick("engineer")
            else -> pick("researcher") ?: pick("engineer")
        } ?: team.members.firstOrNull()?.name.orEmpty()
    }

    /**
     * The report the calling agent reads.
     *
     * Leads with the verdict and the dissent, because the caller is a model
     * deciding what to do next. The token count says "tahmini" when it is one —
     * a made-up precise number is worse than an honest estimate.
     */
    fun format(outcome: CouncilOutcome, policy: CouncilPolicy.Preset, runId: String): String = buildString {
        appendLine(if (outcome.verified) "STATUS: done" else "STATUS: not verified")
        appendLine("Stopped by: ${outcome.stoppedBy.name.lowercase()} · rounds: ${outcome.rounds} · " +
            "tokens: ${outcome.tokensUsed} (tahmini, üyeler için)")
        appendLine("Device: ${policy.label} — ${policy.reason}")
        appendLine("Run: $runId")
        appendLine()
        appendLine("CONSENSUS:")
        appendLine("  unanimous ${outcome.consensus.unanimous.size} · " +
            "decided ${outcome.consensus.decided.size} · " +
            "contested ${outcome.consensus.contested.size} · " +
            "unresolved ${outcome.consensus.unresolved.size}")
        outcome.dissent.take(6).forEach { appendLine("  dissent: $it") }
        appendLine()
        appendLine("RESULT:")
        appendLine(outcome.answer.take(8000))
        val audit = outcome.audit
        if (audit != null && !audit.passed) {
            appendLine()
            appendLine("VERIFICATION PROBLEMS:")
            audit.problems.forEach { appendLine("- $it") }
        }
    }
}
