package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobOrigin
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.diagnostics.BackgroundGate
import com.openminis.app.diagnostics.BackgroundGates
import com.openminis.app.diagnostics.MemorySnapshot
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [MINIS-OZEL] Settings › Live tasks — everything the app is doing right now.
 *
 * The app already knew all of this, but only in separate places: the queue
 * counters lived in [SessionConcurrencyManager], the delegated runs in
 * [AgentJobRegistry], the process figures in [MemorySnapshot], and none of it
 * was reachable without the debug RPC server. That made "why is my phone warm"
 * and "what is still running" unanswerable from the UI.
 *
 * Everything here is read from the same StateFlows the agent loop writes to, so
 * the screen cannot drift from what is actually executing. Nothing is polled
 * except the memory block, which has no flow to subscribe to.
 */
@Composable
fun LiveTasksScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val scope = rememberCoroutineScope()

    val running by SessionConcurrencyManager.runningSessions.collectAsState()
    val queued by SessionConcurrencyManager.suspendedSessions.collectAsState()
    val allJobs by AgentJobRegistry.jobs.collectAsState()

    // Session titles are only in the DB; resolve them once per id and keep the
    // map so a recomposition does not re-query for every row.
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

    // Memory has no StateFlow behind it; sample it while the screen is open.
    var mem by remember { mutableStateOf(MemorySnapshot.capture()) }
    LaunchedEffect(Unit) {
        while (true) {
            mem = MemorySnapshot.capture()
            delay(2_000)
        }
    }

    // Gates are OS state, not app state, so they are re-read every time this
    // screen is entered and whenever the user comes back from a fix intent.
    var gates by remember { mutableStateOf(BackgroundGates.collect(context)) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                gates = BackgroundGates.collect(context)
            }
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
        // ── Background gates ──────────────────────────────────────────────
        // Placed first: a task that dies with the screen off is usually one of
        // these, not a bug, and it is the fastest thing to rule out.
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

        // ── Running agent turns ───────────────────────────────────────────
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
            running.forEachIndexed { index, sessionId ->
                SettingsRow(
                    title = titles[sessionId] ?: sessionId.take(8),
                    subtitle = stringResource(R.string.live_tasks_status_running),
                    icon = Icons.Outlined.SmartToy,
                    iconColor = Color(0xFF34C759),
                    onClick = { onOpenSession(sessionId) },
                    showDivider = index < running.size - 1 || queued.isNotEmpty(),
                )
            }
            queued.forEachIndexed { index, sessionId ->
                SettingsRow(
                    title = titles[sessionId] ?: sessionId.take(8),
                    subtitle = stringResource(R.string.live_tasks_status_queued),
                    icon = Icons.Outlined.HourglassEmpty,
                    iconColor = Color(0xFFFF9500),
                    onClick = { onOpenSession(sessionId) },
                    showDivider = index < queued.size - 1,
                )
            }
        }

        // ── Delegated / scheduled runs ────────────────────────────────────
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
            activeJobs.sortedBy { it.state.ordinal }.forEachIndexed { index, job ->
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
                    trailing = {
                        androidx.compose.material3.IconButton(
                            onClick = {
                                AgentJobRegistry.cancel(job.id, "cancelled from Live tasks")
                            },
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Outlined.Cancel,
                                contentDescription = stringResource(R.string.live_tasks_cancel),
                                tint = Color(0xFFFF3B30),
                            )
                        }
                    },
                    onClick = job.runSessionId?.let { sid -> { onOpenSession(sid) } },
                    showDivider = index < activeJobs.size - 1,
                )
            }
        }

        // ── Scheduled tasks queued in AlarmManager ────────────────────────
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

        Spacer(Modifier.height(24.dp))
    }
}

/** "RUNNING · SUB_AGENT · 2/3 · model" — the tuple that answers "what is this". */
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
