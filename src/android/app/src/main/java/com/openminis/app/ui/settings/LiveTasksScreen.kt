package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobOrigin
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.diagnostics.BackgroundGates
import com.openminis.app.diagnostics.MemorySnapshot
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * [MINIS-OZEL] Settings › Live tasks.
 *
 * Two questions, in the order they get asked: *is anything running*, and *what
 * is it actually doing right now*. The second one is why this screen subscribes
 * to the transcript instead of showing counters.
 *
 * The agent loop already writes every toolUse/toolResult into
 * `messages.parts_json`; [AgentStepExtractor] turns those rows into steps. So a
 * task expanded here replays the real calls, and a call with no result yet is
 * drawn as running — that is a live turn, not a guess from a timer.
 *
 * Everything else on the screen is OS state (permissions) or process state
 * (memory), both read live rather than cached.
 */
@Composable
fun LiveTasksScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp

    val running by SessionConcurrencyManager.runningSessions.collectAsState()
    val queued by SessionConcurrencyManager.suspendedSessions.collectAsState()
    val allJobs by AgentJobRegistry.jobs.collectAsState()

    // Session titles live in the DB only; resolve each id once and keep the map
    // so a recomposition does not re-query for every row.
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(running, queued) {
        val missing = (running + queued).filter { it !in titles }
        if (missing.isEmpty()) return@LaunchedEffect
        val resolved = missing.mapNotNull { id ->
            runCatching { app.chatRepository.getSession(id) }
                .getOrNull()
                ?.let { id to (it.title?.takeIf { t -> t.isNotBlank() } ?: id.take(8)) }
        }.toMap()
        if (resolved.isNotEmpty()) titles = titles + resolved
    }

    // Memory has no StateFlow behind it, so it is sampled while the screen is up.
    var mem by remember { mutableStateOf(MemorySnapshot.capture()) }
    LaunchedEffect(Unit) {
        while (true) {
            mem = MemorySnapshot.capture()
            delay(2_000)
        }
    }

    // Gates are OS state: re-read on resume, because the user leaves to fix one
    // in system Settings and comes straight back.
    var gates by remember { mutableStateOf(BackgroundGates.collect(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) gates = BackgroundGates.collect(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val blockedGates = gates.count { !it.granted }

    val activeJobs = allJobs.values.filter {
        it.state == AgentJobState.RUNNING || it.state == AgentJobState.PENDING
    }
    val scheduledJobs = allJobs.values.filter { it.origin == AgentJobOrigin.SCHEDULED }

    SettingsScaffold(
        title = stringResource(R.string.live_tasks_title),
        onBack = onBack,
    ) {
        // ── Running turns, expanded into their steps ──────────────────────
        SettingsSection(
            header = stringResource(R.string.live_tasks_section_running, running.size),
            footer = stringResource(R.string.live_tasks_running_footer),
        ) {
            if (running.isEmpty() && queued.isEmpty()) {
                SettingsCardBlock {
                    Text(
                        text = stringResource(R.string.live_tasks_idle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
            running.forEach { sessionId ->
                LiveSessionCard(
                    sessionId = sessionId,
                    title = titles[sessionId] ?: sessionId.take(8),
                    statusLabel = stringResource(R.string.live_tasks_status_running),
                    live = true,
                    onOpenChat = { onOpenSession(sessionId) },
                )
                Spacer(Modifier.height(8.dp))
            }
            queued.forEach { sessionId ->
                // A queued turn has no tool calls yet by definition, so it is a
                // plain row — expanding it would only say "nothing ran".
                SettingsRow(
                    title = titles[sessionId] ?: sessionId.take(8),
                    subtitle = stringResource(R.string.live_tasks_status_queued),
                    icon = Icons.Outlined.HourglassEmpty,
                    iconColor = Color(0xFFFF9500),
                    onClick = { onOpenSession(sessionId) },
                    showDivider = sessionId != queued.last(),
                )
            }
        }

        // ── Delegated runs ────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.live_tasks_section_jobs, activeJobs.size),
            footer = stringResource(R.string.live_tasks_jobs_footer),
        ) {
            if (activeJobs.isEmpty()) {
                SettingsCardBlock {
                    Text(
                        text = stringResource(R.string.live_tasks_no_jobs),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
            activeJobs.sortedBy { it.state.ordinal }.forEach { job ->
                val runSession = job.runSessionId
                if (runSession != null) {
                    // A delegated run writes into its own chat, so its steps are
                    // readable there — same live view as a normal turn.
                    LiveSessionCard(
                        sessionId = runSession,
                        title = job.title.ifBlank { job.id.take(8) },
                        statusLabel = jobSubtitle(job),
                        live = job.state == AgentJobState.RUNNING,
                        onOpenChat = { onOpenSession(runSession) },
                        trailing = {
                            CancelJobButton(job.id)
                        },
                    )
                } else {
                    SettingsRow(
                        title = job.title.ifBlank { job.id.take(8) },
                        subtitle = jobSubtitle(job),
                        icon = if (job.origin == AgentJobOrigin.SCHEDULED) {
                            Icons.Outlined.Schedule
                        } else {
                            Icons.Outlined.Terminal
                        },
                        iconColor = if (job.origin == AgentJobOrigin.SCHEDULED) {
                            Color(0xFF30B0C7)
                        } else {
                            Color(0xFF5E5CE6)
                        },
                        trailing = { CancelJobButton(job.id) },
                        showChevron = false,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // ── Scheduled (fires with the app closed) ─────────────────────────
        if (scheduledJobs.isNotEmpty()) {
            SettingsSection(
                header = stringResource(R.string.live_tasks_section_scheduled, scheduledJobs.size),
                footer = stringResource(R.string.live_tasks_scheduled_footer),
            ) {
                scheduledJobs.forEachIndexed { index, job ->
                    SettingsRow(
                        title = job.title.ifBlank { job.id.take(8) },
                        subtitle = jobSubtitle(job),
                        icon = Icons.Outlined.Schedule,
                        iconColor = Color(0xFF30B0C7),
                        showChevron = false,
                        showDivider = index < scheduledJobs.size - 1,
                    )
                }
            }
        }

        // ── Memory ────────────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.live_tasks_section_memory),
            footer = stringResource(R.string.live_tasks_memory_footer),
        ) {
            SettingsRow(
                title = stringResource(
                    R.string.live_tasks_heap,
                    mem.javaHeapMB,
                    mem.javaHeapMaxMB,
                    (mem.javaHeapUsedFraction * 100).toInt(),
                ),
                subtitle = stringResource(
                    R.string.live_tasks_rss,
                    mem.rssMB,
                    mem.rssPeakMB,
                    mem.vmSizeMB,
                ),
                icon = Icons.Outlined.Memory,
                iconColor = if (mem.javaHeapUsedFraction > 0.80) Color(0xFFFF3B30) else Color(0xFF5856D6),
                showChevron = false,
                showDivider = false,
            )
        }

        // ── Background gates ──────────────────────────────────────────────
        // Last, but it is the answer whenever a task died with the screen off.
        SettingsSection(
            header = if (blockedGates == 0) {
                stringResource(R.string.gate_section_ok)
            } else {
                stringResource(R.string.gate_section_blocked, blockedGates)
            },
            footer = stringResource(R.string.gate_section_footer),
        ) {
            gates.forEachIndexed { index, gate ->
                SettingsRow(
                    title = stringResource(gate.titleRes),
                    subtitle = stringResource(gate.detailRes),
                    icon = if (gate.granted) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    iconColor = if (gate.granted) Color(0xFF34C759) else Color(0xFFFF9500),
                    onClick = gate.fixIntent?.let { intent -> { runCatching { context.startActivity(intent) } } },
                    showDivider = index < gates.size - 1,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * One live task, wired to its transcript.
 *
 * `observeMessages` is a Room Flow, so the step list updates on write — there is
 * no polling and no window in which the panel shows a stale step. Only the tail
 * of the transcript is parsed: a long chat's early tool calls are not part of
 * "what is happening now", and keeping the window small keeps the cost flat.
 */
@Composable
private fun LiveSessionCard(
    sessionId: String,
    title: String,
    statusLabel: String,
    live: Boolean,
    onOpenChat: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as MinisApp
    val flow = remember(sessionId) { app.chatRepository.observeMessages(sessionId) }
    val messages by flow.collectAsState(initial = emptyList())
    val steps = remember(messages) {
        AgentStepExtractor.extract(messages.takeLast(TRANSCRIPT_TAIL))
    }
    var expanded by remember(sessionId) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f)) {
            LiveTaskCard(
                title = title,
                statusLabel = if (live) {
                    // The freshest call is the honest "you are here" marker.
                    steps.lastOrNull()?.let { s ->
                        s.description ?: s.name
                    }?.let { "$statusLabel · $it" } ?: statusLabel
                } else {
                    statusLabel
                },
                steps = steps,
                running = live,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                onOpenChat = onOpenChat,
            )
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 4.dp, top = 2.dp)) { trailing() }
        }
    }
}

/** Messages parsed per card. Enough for an active turn, flat in cost. */
private const val TRANSCRIPT_TAIL = 24

@Composable
private fun CancelJobButton(jobId: String) {
    androidx.compose.material3.IconButton(
        onClick = { AgentJobRegistry.cancel(jobId, "cancelled from Live tasks") },
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Outlined.Cancel,
            contentDescription = stringResource(R.string.live_tasks_cancel),
            tint = Color(0xFFFF3B30),
        )
    }
}

/** "RUNNING · agent · model · tier" — the tuple that answers "what is this". */
@Composable
private fun jobSubtitle(job: AgentJob): String {
    val state = when (job.state) {
        AgentJobState.RUNNING -> stringResource(R.string.live_tasks_status_running)
        AgentJobState.PENDING -> stringResource(R.string.live_tasks_status_pending)
        AgentJobState.DONE -> stringResource(R.string.live_tasks_status_done)
        AgentJobState.FAILED -> stringResource(R.string.live_tasks_status_failed)
        AgentJobState.CANCELLED -> stringResource(R.string.live_tasks_status_cancelled)
        AgentJobState.TIMEOUT -> stringResource(R.string.live_tasks_status_timeout)
    }
    val parts = mutableListOf(state)
    job.agentName?.takeIf { it.isNotBlank() }?.let { parts += it }
    job.modelOrigin?.takeIf { it.isNotBlank() }?.let { parts += it }
    job.tierUsed?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(" · ")
}
