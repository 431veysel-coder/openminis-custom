package com.openminis.app.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.team.CouncilLiveRegistry
import com.openminis.app.agent.team.TeamRunStore
import androidx.compose.ui.res.stringResource
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

/**
 * [MINIS-OZEL 11.5 · 37064] The council run screen: agenda, floor, decisions.
 *
 * ## The three tabs, and why not one screen
 *
 * The user opens a council in two moods: "what came out" (five seconds) and "why
 * did it come out that way" (thirty seconds). Those want opposite densities, and
 * a single screen that satisfies both satisfies neither. So:
 *
 *  - **Kürsü** (floor) — what is happening right now. Member cards with the tool
 *    each one is running, which is the answer to "why has it been quiet for forty
 *    seconds".
 *  - **Gündem** (agenda) — the numbered claims and their vote counts. This is
 *    where "who is arguing what" lives, and where a task will attach in 37056.
 *  - **Karar** (decision) — the synthesised answer, the dissent verbatim, and the
 *    stop reason.
 *
 * ## Where the data comes from
 *
 * [CouncilLiveRegistry] for a running (or just-finished) run,
 * [TeamRunStore] for the report of one that ended in an earlier session. The
 * screen never re-derives anything: if it is not in one of those two, it does not
 * exist, and inventing a placeholder for it would be a lie about what happened.
 */
@Composable
fun CouncilScreen(
    runId: String,
    onBack: () -> Unit,
    onOpenMember: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runs by CouncilLiveRegistry.runs.collectAsState()
    val live = runs[runId]
    // Read the finished record once; it does not change while the screen is open.
    val recorded = remember(runId) { TeamRunStore.list(context).firstOrNull { it.runId == runId } }
    val report = remember(runId) { TeamRunStore.report(context, runId) }

    var tab by remember { mutableStateOf(0) }

    SettingsScaffold(
        title = live?.teamName ?: recorded?.teamName ?: stringResource(R.string.council_title),
        onBack = onBack,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: the one line that answers "is it still going".
            SettingsSection {
                SettingsRow(
                    title = statusLine(live, recorded),
                    subtitle = live?.task?.take(200) ?: recorded?.let { it.rounds.toString() + " tur" },
                    showDivider = false,
                )
            }

            TabStrip(selected = tab, onSelect = { tab = it })

            when (tab) {
                0 -> FloorTab(live = live, onOpenMember = onOpenMember)
                1 -> AgendaTab(live = live)
                2 -> RunTasksSection(runId = runId)
                else -> DecisionTab(
                    answer = report,
                    dissent = live?.dissent.orEmpty(),
                    recorded = recorded,
                )
            }
        }
    }
}

@Composable
private fun statusLine(
    live: CouncilLiveRegistry.LiveRun?,
    recorded: TeamRunStore.Header?,
): String = when {
    live?.finished != null ->
        "${live.finished.stoppedBy} · ${live.finished.rounds} tur · ${live.finished.tokensUsed} token"
    live != null ->
        "${phaseLabel(live.phase)} · tur ${live.round}/${live.roundsPlanned} · ${live.tokensUsed} token"
    recorded != null -> recorded.statusLine()
    else -> stringResource(R.string.council_run_gone)
}

private fun phaseLabel(phase: CouncilLiveRegistry.Phase): String = when (phase) {
    CouncilLiveRegistry.Phase.STARTING -> "hazırlanıyor"
    CouncilLiveRegistry.Phase.POSITIONS -> "üyeler yazıyor"
    CouncilLiveRegistry.Phase.CANONICALIZING -> "başkan maddeleri birleştiriyor"
    CouncilLiveRegistry.Phase.DEBATING -> "tartışma"
    CouncilLiveRegistry.Phase.SYNTHESIZING -> "sentez yazılıyor"
    CouncilLiveRegistry.Phase.VERIFYING -> "atıflar denetleniyor"
    CouncilLiveRegistry.Phase.DONE -> "bitti"
}

@Composable
private fun TabStrip(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(
        stringResource(R.string.council_tab_floor),
        stringResource(R.string.council_tab_agenda),
        stringResource(R.string.task_tab),
        stringResource(R.string.council_tab_decision),
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(labels.size) { i ->
            androidx.compose.material3.TextButton(onClick = { onSelect(i) }) {
                Text(
                    labels[i],
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected == i) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The floor: one card per member.
 *
 * The tool badge is the whole point. Without it a member that has been in a
 * browser for forty seconds looks identical to one that crashed, and the user's
 * only recourse is to kill a run that is working fine.
 */
@Composable
private fun FloorTab(
    live: CouncilLiveRegistry.LiveRun?,
    onOpenMember: (String) -> Unit,
) {
    if (live == null) {
        SettingsSection {
            SettingsRow(
                title = stringResource(R.string.council_run_gone),
                subtitle = stringResource(R.string.council_run_gone_hint),
                showDivider = false,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        items(live.members.values.toList()) { member ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (member.absent) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                ),
                onClick = { if (member.sessionId != null) onOpenMember(member.name) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(member.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            member.tool ?: if (member.absent) "yok" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (member.role.isNotBlank()) {
                        Text(
                            member.role,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (member.claim.isNotBlank()) {
                        Text(
                            member.claim,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (member.absent) {
                        Text(
                            stringResource(R.string.council_member_absent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaTab(live: CouncilLiveRegistry.LiveRun?) {
    val claims = live?.claims.orEmpty()
    if (claims.isEmpty()) {
        SettingsSection {
            SettingsRow(
                title = stringResource(R.string.council_no_claims),
                showDivider = false,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        items(claims) { claim ->
            SettingsRow(
                title = "${claim.id} · ${claim.text}",
                subtitle = "${claim.support}✓ ${claim.oppose}✗ ${claim.uncertain}? · ${claim.verdict}",
                showDivider = true,
            )
        }
    }
}

@Composable
private fun DecisionTab(
    answer: String?,
    dissent: List<String>,
    recorded: TeamRunStore.Header?,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        item {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (recorded != null && !recorded.verified) {
                    Text(
                        stringResource(R.string.council_unverified),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    answer ?: stringResource(R.string.council_no_report),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (dissent.isNotEmpty()) {
                    Text(
                        stringResource(R.string.council_dissent),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    dissent.forEach {
                        Text(
                            "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * [MINIS-OZEL 11.5 · 37064] Every council run the user can open.
 *
 * Running runs first (they are what the user is waiting on), then the recorded
 * ones newest first. A run that is in the registry is NOT read from disk — the
 * disk copy does not exist until the run finishes, so showing it would mean
 * showing yesterday's file for today's run id.
 */
@Composable
fun CouncilRunsScreen(
    onBack: () -> Unit,
    onOpenRun: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val live by CouncilLiveRegistry.runs.collectAsState()
    val recorded = remember(live.size) {
        runCatching { TeamRunStore.list(context) }.getOrDefault(emptyList())
    }
    val liveRuns = live.values.sortedByDescending { it.startedAtMs }
    val liveIds = liveRuns.map { it.runId }.toSet()

    SettingsScaffold(
        title = stringResource(R.string.council_title),
        onBack = onBack,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (liveRuns.isNotEmpty()) {
                SettingsSection(header = stringResource(R.string.council_section_running)) {
                    liveRuns.forEachIndexed { i, run ->
                        SettingsRow(
                            title = run.teamName,
                            subtitle = "${phaseLabel(run.phase)} · tur ${run.round}/${run.roundsPlanned}",
                            showDivider = i < liveRuns.lastIndex,
                            onClick = { onOpenRun(run.runId) },
                        )
                    }
                }
            }
            val past = recorded.filter { it.runId !in liveIds }
            SettingsSection(
                header = stringResource(R.string.council_section_past),
                footer = if (past.isEmpty()) stringResource(R.string.council_empty_hint) else null,
            ) {
                if (past.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.council_empty_title),
                        subtitle = null,
                        showDivider = false,
                    )
                } else {
                    past.forEachIndexed { i, run ->
                        SettingsRow(
                            title = run.teamName,
                            subtitle = run.statusLine(),
                            showDivider = i < past.lastIndex,
                            onClick = { onOpenRun(run.runId) },
                        )
                    }
                }
            }
        }
    }
}
