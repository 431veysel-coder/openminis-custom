package com.openminis.app.ui.team

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.team.Team
import com.openminis.app.data.team.TeamStore
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

/**
 * [MINIS-OZEL 11.4] Home toolbar › Ekip.
 *
 * Slice 1 owns the entry point and the roster storage: creating, naming and
 * deleting a team works and survives a restart, because a team is a plain JSON
 * file (see [TeamStore]). What a team can DO — the orchestrator loop, member
 * tool belts, peer questions — arrives on top of this store, so the screen
 * never has to be redesigned to accept it.
 *
 * Deliberately no member editor here yet: adding members without a run loop
 * would produce teams that look finished and do nothing.
 */
@Composable
fun TeamsScreen(onBack: () -> Unit, onOpenTeam: (String) -> Unit) {
    val context = LocalContext.current
    // Re-read on every mutation: the list is a handful of small files, and a
    // cached copy would go stale the moment another screen writes one.
    var generation by remember { mutableStateOf(0) }
    val teams = remember(generation) { TeamStore.list(context) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Team?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.teams_title),
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    newName = ""
                    showCreate = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.teams_new)) },
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 96.dp),
        ) {
            SettingsSection(
                header = stringResource(R.string.teams_section_rosters),
                footer = stringResource(R.string.teams_footer),
            ) {
                if (teams.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.teams_empty_title),
                        subtitle = stringResource(R.string.teams_empty_subtitle),
                        showDivider = false,
                    )
                } else {
                    teams.forEachIndexed { index, team ->
                        SettingsRow(
                            title = team.name,
                            subtitle = memberSummary(team),
                            onClick = { onOpenTeam(team.id) },
                            showDivider = index < teams.lastIndex,
                            trailing = {
                                IconButton(onClick = { pendingDelete = team }) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = stringResource(R.string.teams_delete),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.teams_new)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.teams_name_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(Team.MAX_NAME_CHARS) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        placeholder = { Text(stringResource(R.string.teams_name_placeholder)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        TeamStore.create(context, newName)
                        showCreate = false
                        generation++
                    },
                ) { Text(stringResource(R.string.teams_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingDelete?.let { team ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.teams_delete)) },
            text = { Text(stringResource(R.string.teams_delete_confirm, team.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        TeamStore.delete(context, team.id)
                        pendingDelete = null
                        generation++
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * One line under a team's name. Until the run loop exists a roster holds no
 * members, so the honest subtitle says what the team IS rather than pretending
 * to a state it has not reached.
 */
@Composable
private fun memberSummary(team: Team): String =
    if (team.members.isEmpty()) {
        stringResource(R.string.teams_row_no_members)
    } else {
        stringResource(R.string.teams_row_members, team.members.size)
    }
