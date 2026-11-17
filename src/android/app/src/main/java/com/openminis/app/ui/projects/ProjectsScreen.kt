package com.openminis.app.ui.projects

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

/**
 * [MINIS-OZEL 11.4] Home toolbar › Projeler.
 *
 * A project starts life as a session group: the app already stores groups
 * (`folders`), already files sessions into them and already titles them, so
 * this screen is a real directory of real data from the first build. What a
 * project will ADD — its own working directory, notes, task board and the
 * capability set (tools, MCP servers, skills, scope, budget) applied to every
 * session inside it — layers onto this list without changing its shape.
 *
 * Counts are read once per folder list change rather than kept in a Flow: a
 * group's member count changes only when a session is created, moved or
 * deleted, and those all re-emit the folder list.
 */
@Composable
fun ProjectsScreen(
    chatRepository: ChatRepository,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
) {
    val folders by chatRepository.observeFolders().collectAsState(initial = emptyList<FolderEntity>())
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(folders) {
        counts = folders.associate { it.id to chatRepository.sessionCountInFolder(it.id) }
    }

    SettingsScaffold(title = stringResource(R.string.projects_title), onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
        ) {
            SettingsSection(
                header = stringResource(R.string.projects_section_all),
                footer = stringResource(R.string.projects_footer),
            ) {
                if (folders.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.projects_empty_title),
                        subtitle = stringResource(R.string.projects_empty_subtitle),
                        showDivider = false,
                    )
                } else {
                    folders.forEachIndexed { index, folder ->
                        SettingsRow(
                            title = folder.name,
                            subtitle = folder.description
                                ?: stringResource(R.string.projects_row_sessions, counts[folder.id] ?: 0),
                            icon = Icons.Outlined.Folder,
                            onClick = { onOpenProject(folder.id) },
                            showDivider = index < folders.lastIndex,
                        )
                    }
                }
            }
            // One line of honesty about what this list is not yet: the header
            // says "Projeler", so the copy must not imply capabilities the build
            // does not have.
            Text(
                text = stringResource(R.string.projects_scope_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp),
            )
        }
    }
}
