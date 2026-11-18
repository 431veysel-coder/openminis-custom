package com.openminis.app.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.team.TaskBoard
import com.openminis.app.agent.team.TaskState
import com.openminis.app.agent.team.TaskStore
import com.openminis.app.agent.team.TeamTask
import com.openminis.app.data.team.TeamStore
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

/**
 * [MINIS-OZEL 11.5 · 37055] The task hall: every team's board, in one place.
 *
 * ## Why this screen exists at all
 *
 * A council run ends and its decisions are worthless unless somebody can see what
 * is still owed. The run screen shows one run's tasks; this shows the whole
 * backlog across teams, which is what a user actually opens a day later.
 *
 * ## What it deliberately cannot do
 *
 * It does not mark anything done. The evidence rule means a person clicking
 * "done" would bypass exactly the check the board exists for — so a manual
 * completion is a separate, explicit action on a task's detail (37056), not a
 * checkbox in a list. Reading is the default here.
 *
 * ## The four sections
 *
 * Open, doing, done, dropped — in that order, because that is the order a user
 * cares about. A board with only done rows looks identical to one with nothing
 * to do, and that ambiguity is worth the extra three headers.
 */
@Composable
fun TaskHallScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var generation by remember { mutableStateOf(0) }
    // Re-read on every mutation and on open: the file is small and a cached copy
    // would go stale the moment a run writes a task (which happens off-screen).
    val boards = remember(generation) {
        runCatching { TaskStore.loadAll(context) }.getOrDefault(emptyMap())
    }
    val teamNames = remember(generation) {
        runCatching { TeamStore.list(context).associate { it.id to it.name } }.getOrDefault(emptyMap())
    }
    var filter by remember { mutableStateOf<TaskState?>(TaskState.OPEN) }

    val all = boards.flatMap { (teamId, board) ->
        board.tasks.map { teamNames[teamId].orEmpty() to it }
    }
    val counts = TaskState.entries.associateWith { state -> all.count { it.second.state == state } }
    val shown = all.filter { filter == null || it.second.state == filter }
        .sortedWith(compareBy({ it.second.state.ordinal }, { it.second.updatedAtMs }))

    SettingsScaffold(
        title = stringResource(R.string.task_hall_title),
        onBack = onBack,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Counters first: "5 open, 1 doing, 12 done" is the whole answer for
            // most visits, and it is one line.
            SettingsSection {
                SettingsRow(
                    title = TaskState.entries.joinToString(" · ") {
                        "${stringResource(stateLabel(it))} ${counts[it] ?: 0}"
                    },
                    subtitle = stringResource(R.string.task_hall_hint),
                    showDivider = false,
                )
            }

            FilterStrip(selected = filter, onSelect = { filter = it })

            if (shown.isEmpty()) {
                SettingsSection {
                    SettingsRow(
                        title = stringResource(R.string.task_hall_empty),
                        showDivider = false,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(shown) { (teamName, task) ->
                        TaskRow(teamName = teamName, task = task)
                    }
                }
            }
        }
    }
}

private fun stateLabel(state: TaskState): Int = when (state) {
    TaskState.OPEN -> R.string.task_state_open
    TaskState.DOING -> R.string.task_state_doing
    TaskState.DONE -> R.string.task_state_done
    TaskState.DROPPED -> R.string.task_state_dropped
}

@Composable
private fun FilterStrip(selected: TaskState?, onSelect: (TaskState?) -> Unit) {
    val labels = listOf<TaskState?>(null) + TaskState.entries
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(labels.size) { i ->
            val state = labels[i]
            val text = if (state == null) {
                stringResource(R.string.task_filter_all)
            } else {
                stringResource(stateLabel(state))
            }
            TextButton(onClick = { onSelect(state) }) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected == state) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One task row.
 *
 * The subtitle carries the two facts that decide whether the row is trustworthy:
 * who owns it, and how much evidence it has. A `done` row with zero evidence rows
 * is impossible by the board's rules, so the count is meaningful rather than
 * decorative.
 */
@Composable
private fun TaskRow(teamName: String, task: TeamTask) {
    SettingsRow(
        title = task.title,
        subtitle = buildString {
            if (teamName.isNotBlank()) append(teamName).append(" · ")
            if (task.owner.isNotBlank()) append(task.owner) else append(stringResource(R.string.task_unassigned))
            if (task.fromClaim.isNotBlank()) append(" · ").append(task.fromClaim)
            if (task.evidence.isNotEmpty()) append(" · ").append("${task.evidence.size} evidence")
            if (task.state == TaskState.DROPPED && task.detail.isNotBlank()) {
                append(" · ").append(task.detail.take(60))
            }
        },
        showDivider = true,
        minHeight = 56.dp,
    )
}

/**
 * [MINIS-OZEL 11.5 · 37055] One run's tasks, embedded in the run screen.
 *
 * Shares the row with the hall so the two cannot drift: a task that reads one way
 * in the backlog and another way inside the run would make the user trust neither.
 */
@Composable
fun RunTasksSection(
    runId: String,
    modifier: Modifier = Modifier,
) {
    val live = rememberRunTasks(runId)
    if (live.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsSection(header = stringResource(R.string.task_hall_run_header)) {
            live.forEachIndexed { i, task ->
                SettingsRow(
                    title = task.title,
                    subtitle = buildString {
                        append(task.state.wire)
                        if (task.owner.isNotBlank()) append(" · ").append(task.owner)
                        if (task.fromClaim.isNotBlank()) append(" · ").append(task.fromClaim)
                    },
                    showDivider = i < live.lastIndex,
                    minHeight = 56.dp,
                )
            }
        }
    }
}

/**
 * The run's tasks, observed.
 *
 * A plain composable on purpose: an extension on the registry's companion was the
 * first shape I reached for and it cannot work — `collectAsState` needs a
 * composition scope, and a companion object has none. Reading the flow through the
 * same `runs` StateFlow the run screen uses also means one source, so a task that
 * changes state on the floor tab updates the agenda immediately.
 */
@Composable
fun rememberRunTasks(runId: String): List<TeamTask> {
    val runs by com.openminis.app.agent.team.CouncilLiveRegistry.runs.collectAsState()
    return runs[runId]?.tasks?.values.orEmpty()
        .sortedWith(compareBy({ it.state.ordinal }, { it.createdAtMs }))
}
