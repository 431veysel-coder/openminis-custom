package com.openminis.app.ui.team

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.team.Team
import com.openminis.app.data.team.TeamMember
import com.openminis.app.data.team.TeamStore
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

/**
 * [MINIS-OZEL 11.4 S2] One team: its name and its roster.
 *
 * The editor is deliberately the whole story of a member — a name, a role
 * sentence, and a model. Nothing about tools, because a member's tool belt is
 * inherited from the app (shell, browser, files, MCP): a per-member checkbox
 * grid would be a security surface nobody would read, and it would also be a
 * promise the runner does not yet honour per member. The `TeamMember.tools`
 * field exists in storage for the day it does.
 *
 * The model picker lists only ENABLED providers' visible models, and each row
 * shows its provider — the two things a user needs to avoid picking a
 * credential-less instance and wondering why the team is silent.
 */
@Composable
fun TeamEditorScreen(
    teamId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var team by remember(teamId) { mutableStateOf(TeamStore.get(context, teamId)) }
    var dirty by remember(teamId) { mutableStateOf(false) }
    var memberDialog by remember { mutableStateOf<TeamMember?>(null) }
    var editingMemberIndex by remember { mutableStateOf(-1) }

    val current = team
    if (current == null) {
        SettingsScaffold(title = stringResource(R.string.teams_title), onBack = onBack) {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.teams_missing),
                    subtitle = stringResource(R.string.teams_missing_subtitle),
                    showDivider = false,
                )
            }
        }
        return
    }

    fun persist(next: Team) {
        team = TeamStore.save(context, next)
        dirty = true
    }

    SettingsScaffold(
        title = current.name,
        onBack = onBack,
        actions = {
            if (dirty) {
                TextButton(onClick = {
                    TeamStore.save(context, team ?: current)
                    dirty = false
                }) { Text(stringResource(R.string.teams_save)) }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SettingsSection(header = stringResource(R.string.teams_section_identity)) {
                NameField(
                    value = current.name,
                    label = stringResource(R.string.teams_name_label),
                    onValueChange = { persist(current.copy(name = it.take(Team.MAX_NAME_CHARS))) },
                )
            }

            SettingsSection(
                header = stringResource(R.string.teams_section_members),
                footer = if (current.members.size < Team.MIN_MEMBERS) {
                    stringResource(R.string.teams_need_members, Team.MIN_MEMBERS)
                } else {
                    stringResource(R.string.teams_members_footer)
                },
            ) {
                if (current.members.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.teams_row_no_members),
                        subtitle = stringResource(R.string.teams_add_member_hint),
                        showDivider = false,
                    )
                } else {
                    current.members.forEachIndexed { index, member ->
                        SettingsRow(
                            title = member.name,
                            subtitle = memberSubtitle(context, member),
                            minHeight = 64.dp,
                            onClick = {
                                editingMemberIndex = index
                                memberDialog = member
                            },
                            showDivider = index < current.members.lastIndex,
                            trailing = {
                                IconButton(onClick = {
                                    persist(current.copy(members = current.members.filterIndexed { i, _ -> i != index }))
                                }) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = stringResource(R.string.teams_remove_member),
                                    )
                                }
                            },
                        )
                    }
                }
                SettingsRow(
                    title = stringResource(R.string.teams_add_member),
                    icon = Icons.Default.Add,
                    showDivider = false,
                    titleColor = MaterialTheme.colorScheme.primary,
                    onClick = {
                        if (current.members.size >= Team.MAX_MEMBERS) {
                            memberDialog = null
                        } else {
                            editingMemberIndex = -1
                            memberDialog = TeamMember(name = "")
                        }
                    },
                )
            }
        }
    }

    memberDialog?.let { draft ->
        MemberDialog(
            initial = draft,
            existingNames = current.members.map { it.name },
            onDismiss = { memberDialog = null },
            onConfirm = { member ->
                val members = current.members.toMutableList()
                if (editingMemberIndex in members.indices) {
                    members[editingMemberIndex] = member
                } else {
                    members += member
                }
                persist(current.copy(members = members.take(Team.MAX_MEMBERS)))
                memberDialog = null
            },
        )
    }
}

@Composable
private fun NameField(value: String, label: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun memberSubtitle(context: android.content.Context, member: TeamMember): String {
    val parts = mutableListOf<String>()
    if (member.role.isNotBlank()) parts += member.role
    val label = modelLabel(context, member)
    if (label != null) parts += label
    return parts.joinToString(" · ")
}

/** "DeepSeek V4.1 Flash · Evren API 2" — or a warning when the pin is stale. */
@Composable
private fun modelLabel(context: android.content.Context, member: TeamMember): String? {
    val app = context.applicationContext as? MinisApp ?: return null
    val repository = app.providerRepositoryOrNull ?: return null
    val instanceId = member.providerInstanceId
    val modelId = member.modelId
    if (instanceId.isNullOrBlank() || modelId.isNullOrBlank()) return null
    val entry = repository.entriesFor(instanceId).firstOrNull { it.baseModel.id == modelId }
    val instance = repository.instance(instanceId)
    return when {
        entry == null -> stringResource(R.string.teams_model_missing)
        instance == null -> stringResource(R.string.teams_model_missing)
        else -> "${entry.model.displayName} · ${instance.label}"
    }
}

@Composable
private fun MemberDialog(
    initial: TeamMember,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (TeamMember) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    var role by remember { mutableStateOf(initial.role) }
    var providerInstanceId by remember { mutableStateOf(initial.providerInstanceId) }
    var modelId by remember { mutableStateOf(initial.modelId) }
    var picking by remember { mutableStateOf(false) }

    val app = context.applicationContext as? MinisApp
    val entries: List<ModelEntry> = remember(picking) {
        if (!picking) emptyList() else app?.providerRepositoryOrNull?.allVisibleEntries().orEmpty()
    }
    val chosenLabel = remember(providerInstanceId, modelId) {
        val repo = app?.providerRepositoryOrNull
        val entry = repo?.entriesFor(providerInstanceId.orEmpty())
            ?.firstOrNull { it.baseModel.id == modelId }
        val instance = providerInstanceId?.let { repo?.instance(it) }
        if (entry != null && instance != null) "${entry.model.displayName} · ${instance.label}" else null
    }

    // A duplicate name breaks the ledger: the orchestrator assigns work by name
    // and the parser resolves the speaker by it, so two "Araştırmacı" rows make
    // every assignment ambiguous.
    val duplicate = existingNames.any { it.equals(name.trim(), ignoreCase = true) && it != initial.name }
    val canSave = name.isNotBlank() && !duplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial.name.isBlank()) stringResource(R.string.teams_add_member)
                else stringResource(R.string.teams_edit_member),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.teams_member_name)) },
                    isError = duplicate,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (duplicate) {
                    Text(
                        stringResource(R.string.teams_member_duplicate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it.take(Team.MAX_ROLE_CHARS) },
                    label = { Text(stringResource(R.string.teams_member_role)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.teams_member_role_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(
                    onClick = { picking = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        chosenLabel ?: stringResource(R.string.teams_pick_model),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onConfirm(
                        initial.copy(
                            name = name.trim(),
                            role = role.trim(),
                            providerInstanceId = providerInstanceId,
                            modelId = modelId,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.teams_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.teams_pick_model)) },
            text = {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.teams_no_models))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(entries) { entry ->
                            val instance = app?.providerRepositoryOrNull?.instance(entry.providerInstanceId)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        providerInstanceId = entry.providerInstanceId
                                        modelId = entry.baseModel.id
                                        picking = false
                                    }
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    entry.model.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    instance?.label ?: entry.baseModel.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
