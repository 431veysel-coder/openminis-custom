package com.openminis.app.ui.projects

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Add
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
    // [37126] İşlem durumları. Üçü de tek ekranda: ayrı ekran/ViewModel
    // açmak, iki TextField için orantısız olurdu.
    var creating by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<FolderEntity?>(null) }
    var renaming by remember { mutableStateOf<FolderEntity?>(null) }
    var dissolving by remember { mutableStateOf<FolderEntity?>(null) }
    val scope = rememberCoroutineScope()

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
                // [37126] Depo tam CRUD sunuyordu, ekran hiçbirini çağırmıyordu
                // (ölçüm: 4 fonksiyonun 0 çağıranı). Oluşturma artık burada —
                // kullanıcı proje açmak için Sohbetler ekranına gitmiyor.
                SettingsRow(
                    title = stringResource(R.string.projects_new),
                    icon = Icons.Outlined.Add,
                    iconColor = MaterialTheme.colorScheme.primary,
                    onClick = { creating = true },
                    showDivider = folders.isNotEmpty(),
                )
                if (folders.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.projects_empty_title),
                        subtitle = stringResource(R.string.projects_empty_subtitle),
                        showDivider = false,
                    )
                } else {
                    folders.forEachIndexed { index, folder ->
                        SettingsRow(
                            title = if (ProjectRules.isNameless(folder.name)) {
                                stringResource(R.string.projects_unnamed)
                            } else {
                                folder.name
                            },
                            subtitle = folder.description
                                ?: stringResource(R.string.projects_row_sessions, counts[folder.id] ?: 0),
                            icon = if (folder.isPinned) Icons.Outlined.PushPin else Icons.Outlined.Folder,
                            onClick = { onOpenProject(folder.id) },
                            // Uzun basma: yeniden adlandır / sabitle / çöz.
                            // Ayrı bir "düzenle" düğmesi her satırı kalabalık
                            // yapar; üç işlem de nadir.
                            onLongClick = { acting = folder },
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

        if (creating) {
            ProjectNameDialog(
                title = stringResource(R.string.projects_new),
                initialName = "",
                initialDescription = "",
                confirmLabel = stringResource(R.string.projects_create),
                onDismiss = { creating = false },
                onConfirm = { name, desc ->
                    creating = false
                    scope.launch { chatRepository.createFolder(name, desc) }
                },
            )
        }

        renaming?.let { folder ->
            ProjectNameDialog(
                title = stringResource(R.string.projects_rename),
                initialName = folder.name,
                initialDescription = folder.description.orEmpty(),
                confirmLabel = stringResource(R.string.save),
                onDismiss = { renaming = null },
                onConfirm = { name, desc ->
                    renaming = null
                    scope.launch { chatRepository.renameFolder(folder.id, name, desc) }
                },
            )
        }

        acting?.let { folder ->
            AlertDialog(
                onDismissRequest = { acting = null },
                title = { Text(folder.name) },
                text = {
                    Column {
                        TextButton(onClick = {
                            acting = null
                            renaming = folder
                        }) { Text(stringResource(R.string.projects_rename)) }
                        TextButton(onClick = {
                            acting = null
                            scope.launch { chatRepository.toggleFolderPin(folder.id) }
                        }) {
                            Text(
                                if (folder.isPinned) stringResource(R.string.projects_unpin)
                                else stringResource(R.string.projects_pin)
                            )
                        }
                        TextButton(onClick = {
                            acting = null
                            dissolving = folder
                        }) {
                            Text(
                                stringResource(R.string.projects_dissolve),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { acting = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        // Yıkıcı işlem: onay ister. `dissolveFolder` klasörü siler, sohbetleri
        // SİLMEZ — grupsuz bırakır. Cümle bunu açıkça söylemek zorunda, yoksa
        // kullanıcı iki yönde de yanlış karar verir.
        dissolving?.let { folder ->
            val inside = counts[folder.id] ?: 0
            AlertDialog(
                onDismissRequest = { dissolving = null },
                title = { Text(stringResource(R.string.projects_dissolve)) },
                text = {
                    Text(
                        when (ProjectRules.dissolveShape(inside)) {
                            ProjectRules.DissolveShape.EMPTY ->
                                stringResource(R.string.projects_dissolve_empty, folder.name)
                            ProjectRules.DissolveShape.ONE ->
                                stringResource(R.string.projects_dissolve_one, folder.name)
                            ProjectRules.DissolveShape.MANY ->
                                stringResource(R.string.projects_dissolve_many, folder.name, inside)
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        dissolving = null
                        scope.launch { chatRepository.dissolveFolder(folder.id) }
                    }) {
                        Text(
                            stringResource(R.string.projects_dissolve),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dissolving = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

/**
 * [37126] Ad + açıklama diyaloğu — oluşturma ve yeniden adlandırma aynı formu
 * kullanır. Kaydet düğmesi ad geçersizken **kapalı**: `renameFolder` boş adı
 * sessizce yok sayıyor, yani kullanıcı düğmeye basıp hiçbir şey olmamasını
 * yaşardı. Sessiz hiçbir-şey, bozuk düğmeden ayırt edilemez.
 */
@Composable
private fun ProjectNameDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.projects_name_field)) },
                    singleLine = true,
                    isError = name.isNotBlank() && !ProjectRules.isValidName(name),
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text(stringResource(R.string.projects_desc_field)) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ProjectRules.isValidName(name),
                onClick = { onConfirm(name, desc.ifBlank { null }) },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
