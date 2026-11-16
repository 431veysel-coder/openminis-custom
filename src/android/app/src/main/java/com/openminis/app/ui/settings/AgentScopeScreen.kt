package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.scope.AgentScope

/**
 * [MINIS-OZEL 11.3.1] Settings › Minis Özel › Agent scope.
 *
 * Three stances, one tap each, no grid. The footer says the thing that makes
 * this a real choice rather than a nag: whatever goes wrong, the action journal
 * can take it back — so a wider scope is a considered risk, not a leap.
 */
@Composable
fun AgentScopeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AgentScope.mode(context)) }

    SettingsScaffold(
        title = stringResource(R.string.ozel_scope_title),
        onBack = onBack,
    ) {
        SettingsSection(footer = stringResource(R.string.ozel_scope_footer)) {
            AgentScope.Mode.entries.forEachIndexed { index, candidate ->
                SettingsRow(
                    title = scopeLabel(candidate),
                    subtitle = scopeDescription(candidate),
                    showDivider = index != AgentScope.Mode.entries.lastIndex,
                    minHeight = 72.dp,
                    onClick = {
                        mode = candidate
                        AgentScope.setMode(context, candidate)
                    },
                    trailing = if (mode == candidate) {
                        {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun scopeLabel(mode: AgentScope.Mode): String = when (mode) {
    AgentScope.Mode.OPEN -> stringResource(R.string.ozel_scope_open)
    AgentScope.Mode.READ_ONLY -> stringResource(R.string.ozel_scope_read_only)
    AgentScope.Mode.WORKSPACE -> stringResource(R.string.ozel_scope_workspace)
}

@Composable
private fun scopeDescription(mode: AgentScope.Mode): String = when (mode) {
    AgentScope.Mode.OPEN -> stringResource(R.string.ozel_scope_open_desc)
    AgentScope.Mode.READ_ONLY -> stringResource(R.string.ozel_scope_read_only_desc)
    AgentScope.Mode.WORKSPACE -> stringResource(R.string.ozel_scope_workspace_desc)
}
