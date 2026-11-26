package com.openminis.app.ui.team

import androidx.compose.foundation.layout.fillMaxSize
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
        // [37127] ÇÖKME ONARIMI. `SettingsScaffold` içeriği varsayılan olarak
        // `verticalScroll` içine sarar. Bu ekranın gövdesinde bir `LazyColumn`
        // var ve yüksekliği sınırsızdı: Compose iki dikey kaydırılabiliri
        // ölçemez ve şu istisnayı fırlatır —
        //
        //   IllegalStateException: Vertically scrollable component was measured
        //   with an infinity maximum height constraints, which is disallowed.
        //
        // Yani **görev listesi boş değilse ekran açılır açılmaz düşüyordu**;
        // boşken (yalnız "görev yok" satırı) çalışıyordu, bu yüzden kolay
        // gizlendi. Çözüm kod tabanındaki mevcut kalıp: kaydırmayı scaffold'tan
        // al, listeye ver (LogManagement/StorageManagement da böyle yapıyor).
        scrollable = false,
    ) {
        // `fillMaxSize()` ŞART: `weight(1f)` yalnız ebeveynin yüksekliği
        // sınırlıysa "kalan alan" demek. `fillMaxWidth()` ile Column içerik
        // yüksekliğine küçülür, kalan 0 olur ve liste görünmez kalırdı —
        // çökmeyi düzeltirken ekranı boşaltmak en sinsi hata olurdu.
        Column(modifier = Modifier.fillMaxSize()) {
            // Counters first: "5 open, 1 doing, 12 done" is the whole answer for
            // most visits, and it is one line.
            SettingsSection {
                SettingsRow(
                    // `joinToString` is not inline, so `stringResource` (a
                    // @Composable) cannot be called inside its transform — the
                    // compiler rejects it with "@Composable invocations can only
                    // happen from the context of a @Composable function".
                    // `map` IS inline, so the label lookup happens there and the
                    // join is a plain string operation.
                    title = TaskState.entries
                        .map { "${stringResource(stateLabel(it))} ${counts[it] ?: 0}" }
                        .joinToString(" · "),
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
                // `weight(1f)`: Column'un kalan yüksekliğini alır. Sabit bir
                // `heightIn(max=)` de çökmeyi önlerdi ama iki kaydırma bölgesi
                // yaratırdı (üstte sayaçlar + altta liste, kafa karıştırır).
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
            // [37127] Burada sabit Ingilizce "evidence" yaziyordu: Turkce
            // arayuzde Ingilizce kelime. Sayac dize kaynagindan gelir.
            if (task.evidence.isNotEmpty()) {
                append(" · ").append(
                    stringResource(R.string.task_evidence_count, task.evidence.size),
                )
            }
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
    val context = LocalContext.current
    val runs by com.openminis.app.agent.team.CouncilLiveRegistry.runs.collectAsState()
    val run = runs[runId]
    val live = rememberRunTasks(runId)
    var refusal by remember(runId) { mutableStateOf("") }
    var adding by remember(runId) { mutableStateOf(false) }
    var draftTitle by remember(runId) { mutableStateOf("") }
    // [37099f · §1.4] Kanıt ekleme: `TeamTask.canBeDone` kuralı vardı ama
    // kullanıcı kanıt ekleyemiyordu, yani DONE hiç açılmıyordu.
    var evidenceFor by remember(runId) { mutableStateOf<String?>(null) }
    var draftEvidence by remember(runId) { mutableStateOf("") }
    var draftKind by remember(runId) { mutableStateOf(com.openminis.app.agent.team.EvidenceKind.PATH) }
    val members = run?.members?.keys?.toList().orEmpty()

    fun persist(block: (com.openminis.app.agent.team.TaskBoard) -> Pair<com.openminis.app.agent.team.TaskBoard, String>) {
        val teamId = run?.teamId ?: return
        val (board, why) = com.openminis.app.agent.team.TaskStore.update(context, teamId, block)
        refusal = why
        if (why.isEmpty()) {
            // Kayıt tek gerçek: yazılan panoyu canlı kayda da geçir, yoksa
            // ekran diski değil belleği gösterir ve iki kaynak ayrışır.
            board.tasks.forEach { com.openminis.app.agent.team.CouncilLiveRegistry.taskAdded(runId, it) }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (live.isEmpty()) {
            // [37099d] Önce burada `if (live.isEmpty()) return` vardı: boş görev
            // odası **hiçbir şey** çizmiyordu — ne başlık ne açıklama. Kullanıcı
            // "görev odası boş mu, bozuk mu" ayırt edemiyordu.
            SettingsSection(
                header = stringResource(R.string.task_hall_run_header),
                footer = stringResource(R.string.task_hall_empty_hint),
            ) {
                SettingsRow(
                    title = stringResource(R.string.task_hall_empty_title),
                    showDivider = false,
                )
            }
        } else {
            SettingsSection(header = stringResource(R.string.task_hall_run_header)) {
                live.forEach { task ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SettingsRow(
                            title = task.title,
                            subtitle = buildString {
                                // [37127] `wire` tel bicimi ("open"/"doing"),
                                // kullaniciya Ingilizce gorunuyordu. Ayni
                                // ekrandaki sayaçlar `stateLabel` kullaniyor;
                                // iki oda ayni gorevi iki dilde anlatamaz.
                                append(stringResource(stateLabel(task.state)))
                                if (task.owner.isNotBlank()) append(" · ").append(task.owner)
                                if (task.fromClaim.isNotBlank()) append(" · ").append(task.fromClaim)
                                if (!task.canBeDone && task.state != com.openminis.app.agent.team.TaskState.DONE) {
                                    append(" · kanıt yok")
                                }
                            },
                            showDivider = false,
                            minHeight = 56.dp,
                        )
                        // [37099d] Oda artık salt okunur değil: atama ve düşürme
                        // burada. Sahipsiz görevi "birine ata" demek, ekranda
                        // düğme olmadan anlamsız bir öğüttü.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            members.take(3).forEach { m ->
                                TextButton(onClick = {
                                    persist { b ->
                                        val (nb, why) = b.assign(task.id, m)
                                        nb to why
                                    }
                                }) {
                                    Text("→ $m", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            TextButton(onClick = {
                                evidenceFor = task.id
                                draftEvidence = ""
                                draftKind = com.openminis.app.agent.team.EvidenceKind.PATH
                            }) {
                                Text(
                                    stringResource(R.string.task_hall_add_evidence),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (task.state != com.openminis.app.agent.team.TaskState.DONE) {
                                TextButton(
                                    enabled = task.canBeDone,
                                    onClick = {
                                        persist { b ->
                                            val (nb, _, why) = b.moveTo(
                                                task.id,
                                                com.openminis.app.agent.team.TaskState.DONE,
                                            )
                                            nb to why
                                        }
                                    },
                                ) {
                                    Text(
                                        stringResource(R.string.task_hall_done),
                                        style = MaterialTheme.typography.labelSmall,
                                        // Kural burada görünür: kanıtsız görev
                                        // kapanmaz, düğme de soluk durur.
                                        color = if (task.canBeDone) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            if (task.state != com.openminis.app.agent.team.TaskState.DROPPED) {
                                TextButton(onClick = {
                                    persist { b ->
                                        val (nb, _, why) = b.moveTo(
                                            task.id,
                                            com.openminis.app.agent.team.TaskState.DROPPED,
                                            reason = "kullanıcı düşürdü — görev odasından",
                                        )
                                        nb to why
                                    }
                                }) {
                                    Text(
                                        stringResource(R.string.task_hall_drop),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SettingsSection {
            SettingsRow(
                title = stringResource(R.string.task_hall_add),
                onClick = { adding = true; draftTitle = "" },
                showDivider = false,
            )
        }

        if (refusal.isNotBlank()) {
            Text(
                refusal,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    val evidenceTaskId = evidenceFor
    if (evidenceTaskId != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { evidenceFor = null },
            title = { Text(stringResource(R.string.task_hall_add_evidence)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        com.openminis.app.agent.team.EvidenceKind.entries.forEach { kind ->
                            TextButton(onClick = { draftKind = kind }) {
                                Text(
                                    kind.wire,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (draftKind == kind) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = draftEvidence,
                        onValueChange = { draftEvidence = it },
                        label = { Text(stringResource(R.string.task_hall_add_evidence_hint)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = draftEvidence.trim()
                        if (value.isEmpty()) return@TextButton
                        persist { b ->
                            b.addEvidence(
                                evidenceTaskId,
                                com.openminis.app.agent.team.TaskEvidence(
                                    kind = draftKind,
                                    value = value,
                                    by = "user",
                                ),
                            )
                        }
                        evidenceFor = null
                    },
                ) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { evidenceFor = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (adding) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.task_hall_add)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.task_hall_add_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = draftTitle.trim()
                        if (title.isEmpty()) return@TextButton
                        persist { b ->
                            val (nb, _, why) = b.add(
                                com.openminis.app.agent.team.TeamTask(
                                    id = com.openminis.app.agent.team.TaskStore.newId(),
                                    title = title,
                                    runId = runId,
                                    createdBy = "user",
                                ),
                            )
                            nb to why
                        }
                        adding = false
                    },
                ) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = {
                TextButton(onClick = { adding = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
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
