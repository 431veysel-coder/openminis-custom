package com.openminis.app.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.team.MemberToolBelt
import com.openminis.app.data.team.TeamMode

/**
 * [MINIS-OZEL 11.5 · 37060] The three team modes, as one dialog.
 *
 * A dialog rather than a dropdown: each mode needs a sentence of explanation,
 * and a dropdown row that says only "Council" leaves the user guessing whether
 * that spends ten times the tokens. The descriptions are the whole point of this
 * screen — the mode is the single most expensive decision here.
 */
@Composable
fun TeamModeDialog(
    current: TeamMode,
    councilEnabled: Boolean,
    onDismiss: () -> Unit,
    onPick: (TeamMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.teams_section_mode)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ModeRow(
                    mode = TeamMode.HANDOFF,
                    current = current,
                    title = stringResource(R.string.teams_mode_handoff),
                    description = stringResource(R.string.teams_mode_handoff_desc),
                    enabled = true,
                    onPick = onPick,
                )
                ModeRow(
                    mode = TeamMode.FANOUT,
                    current = current,
                    title = stringResource(R.string.teams_mode_fanout),
                    description = stringResource(R.string.teams_mode_fanout_desc),
                    enabled = true,
                    onPick = onPick,
                )
                ModeRow(
                    mode = TeamMode.COUNCIL,
                    current = current,
                    title = stringResource(R.string.teams_mode_council),
                    description = stringResource(R.string.teams_mode_council_desc),
                    enabled = true,
                    onPick = onPick,
                )
                if (!councilEnabled) {
                    // Shown at the dialog, not only at run time: telling the user
                    // after they picked it is the definition of a dead end.
                    Text(
                        stringResource(R.string.teams_council_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ModeRow(
    mode: TeamMode,
    current: TeamMode,
    title: String,
    description: String,
    enabled: Boolean,
    onPick: (TeamMode) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = mode == current,
            enabled = enabled,
            onClick = { onPick(mode) },
        )
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * [MINIS-OZEL 11.5 · 37060] The member's belt, as chips and checkboxes.
 *
 * ## What changed from 11.4
 *
 * 11.4 refused to show this because the runner did not honour it — a correct
 * call at the time (the comment in `TeamEditorScreen` said so). 37060 makes the
 * belt real at three layers (schema, dispatcher, `team_run`), so the setting can
 * finally be shown without lying.
 *
 * ## Why the role comes first
 *
 * The user's mental model is "this member is a researcher", not "this member has
 * nine tools". So the role chips set the default, and the checkboxes are for the
 * exception. An empty selection means "the role decides", which is also what
 * `TeamMember.tools` already documented — keeping that convention means a team
 * saved by 11.4 opens with the right belt instead of "no tools allowed".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemberBeltSection(
    role: String,
    tools: List<String>,
    onRoleChange: (String) -> Unit,
    onToolsChange: (List<String>) -> Unit,
) {
    val preset = remember(role) { MemberToolBelt.rolePreset(role) ?: MemberToolBelt.MEMBER_ALLOWED }
    val effective = remember(role, tools) {
        if (tools.isEmpty()) preset else (tools.toSet() intersect MemberToolBelt.MEMBER_ALLOWED)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.teams_member_role),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MemberToolBelt.KNOWN_ROLES.forEach { known ->
                val label = when (known) {
                    "researcher" -> stringResource(R.string.teams_member_role_chip_researcher)
                    "engineer" -> stringResource(R.string.teams_member_role_chip_engineer)
                    "reviewer" -> stringResource(R.string.teams_member_role_chip_reviewer)
                    else -> stringResource(R.string.teams_member_role_chip_critic)
                }
                val selected = role.trim().lowercase().startsWith(known)
                TextButton(onClick = { onRoleChange(known) }) {
                    Text(if (selected) "✓ $label" else label)
                }
            }
        }

        Text(
            stringResource(R.string.teams_member_belt),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (tools.isEmpty()) {
                stringResource(R.string.teams_member_belt_role, effective.size)
            } else {
                stringResource(R.string.teams_member_belt_custom, effective.size)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (effective.isEmpty()) {
            Text(
                stringResource(R.string.teams_member_tools_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Column(modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            // Sorted for a stable order: a checkbox grid that reshuffles between
            // opens is one the user cannot learn.
            MemberToolBelt.MEMBER_ALLOWED.sorted().forEach { tool ->
                val checked = tool in effective
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { want ->
                            val next = effective.toMutableSet()
                            if (want) next += tool else next -= tool
                            // Writing the full set is what makes it "custom";
                            // clearing it back to the preset keeps the meaning of
                            // an empty list single.
                            onToolsChange(next.sorted())
                        },
                    )
                    Text(tool, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (tools.isNotEmpty()) {
            TextButton(onClick = { onToolsChange(emptyList()) }) {
                Text(stringResource(R.string.teams_member_belt_reset))
            }
        }
        Text(
            stringResource(R.string.teams_member_belt_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
