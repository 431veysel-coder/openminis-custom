package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.OzelFeatures
import com.openminis.app.data.repository.NoteStore
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.MinisSmallOutlinedButton
import com.openminis.app.ui.components.SectionTextField
import kotlinx.coroutines.launch

/**
 * [MINIS-OZEL 11.1] Settings › Minis Özel › Notes.
 *
 * ## Two lists, one screen, different rules
 *
 * The Notes tab is the agent's notebook: the user may read, edit and delete
 * anything there, and so may the agent. The Vault tab is the opposite — only
 * the user writes there, and the agent can at most read a value, and only after
 * the user turned that on. One screen because the question "where do I keep
 * this?" is asked once, and the answer depends on who should be able to CHANGE
 * it — which is exactly the tab split.
 *
 * ## Editing in place
 *
 * A separate editor route would add a navigation destination for a three-field
 * form and lose the list position on return. The form opens above the list,
 * saves in place, and the list stays where it was.
 */
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { NoteStore.get(context) }
    val all by store.notes.collectAsState()

    var tab by remember { mutableStateOf(Tab.NOTES) }
    var editing by remember { mutableStateOf<NoteStore.Note?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<NoteStore.Note?>(null) }
    var revealId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // Form state, re-derived whenever the target changes.
    val formKey = (editing?.id ?: "new") + ":" + creating
    var title by remember(formKey) { mutableStateOf(editing?.title.orEmpty()) }
    var body by remember(formKey) {
        mutableStateOf(
            editing?.let { row -> if (row.isVault) store.readVault(row.id).orEmpty() else row.body }
                .orEmpty(),
        )
    }
    var tags by remember(formKey) { mutableStateOf(editing?.tags?.joinToString(", ").orEmpty()) }
    var secretVisible by remember(formKey) { mutableStateOf(false) }

    LaunchedEffect(Unit) { store.load() }

    val vaultEnabled = OzelFeatures.isEnabled(context, OzelFeatures.Feature.VAULT)
    val tabs = if (vaultEnabled) listOf(Tab.NOTES, Tab.VAULT) else listOf(Tab.NOTES)
    val rows = when (tab) {
        Tab.NOTES -> all.filter { !it.isVault }
        Tab.VAULT -> all.filter { it.isVault }
    }
    val formOpen = creating || editing != null

    fun resetForm() {
        editing = null
        creating = false
        title = ""
        body = ""
        tags = ""
        secretVisible = false
    }

    SettingsScaffold(
        title = stringResource(R.string.notes_title),
        onBack = onBack,
    ) {
        // ── tabs ───────────────────────────────────────────────────────────
        SettingsSection(
            footer = stringResource(
                if (tab == Tab.NOTES) R.string.notes_footer_notes else R.string.notes_footer_vault,
            ),
        ) {
            SettingsCardBlock {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tabs.forEach { t ->
                        if (tab == t) {
                            MinisSmallButton(onClick = { tab = t; resetForm() }) {
                                Text(stringResource(t.label))
                            }
                        } else {
                            MinisSmallOutlinedButton(onClick = { tab = t; resetForm() }) {
                                Text(stringResource(t.label))
                            }
                        }
                    }
                }
            }
        }

        // ── editor ─────────────────────────────────────────────────────────
        if (formOpen) {
            SettingsSection(header = stringResource(editorHeader(editing, tab))) {
                SettingsCardBlock {
                    SectionTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = stringResource(R.string.notes_field_title),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (tab == Tab.VAULT) {
                        SectionTextField(
                            value = body,
                            onValueChange = { body = it },
                            placeholder = stringResource(R.string.notes_field_secret),
                            visualTransformation = if (secretVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        Spacer(Modifier.height(6.dp))
                        MinisSmallOutlinedButton(onClick = { secretVisible = !secretVisible }) {
                            Icon(
                                imageVector = if (secretVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(
                                    if (secretVisible) R.string.notes_hide else R.string.notes_reveal,
                                ),
                            )
                        }
                    } else {
                        SectionTextField(
                            value = body,
                            onValueChange = { body = it },
                            placeholder = stringResource(R.string.notes_field_body),
                            singleLine = false,
                            maxLines = 12,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SectionTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        placeholder = stringResource(R.string.notes_field_tags),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MinisSmallButton(
                            enabled = title.isNotBlank() && body.isNotBlank(),
                            onClick = {
                                val tagList = tags.split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                val target = editing
                                val inVault = tab == Tab.VAULT
                                scope.launch {
                                    val res = if (inVault) {
                                        store.writeVault(
                                            title = title,
                                            secret = body,
                                            tags = tagList,
                                            fromUi = true,
                                            id = target?.id,
                                        )
                                    } else {
                                        store.write(
                                            scope = target?.scope ?: NoteStore.Scope.GLOBAL.wire,
                                            title = title,
                                            body = body,
                                            tags = tagList,
                                            author = NoteStore.Author.USER,
                                            id = target?.id,
                                        )
                                    }
                                    res.onSuccess {
                                        message = context.getString(R.string.notes_saved)
                                        resetForm()
                                    }.onFailure { message = it.message }
                                }
                            },
                        ) { Text(stringResource(R.string.save)) }
                        MinisSmallOutlinedButton(onClick = { resetForm() }) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            }
        } else {
            SettingsSection {
                SettingsCardBlock {
                    MinisSmallOutlinedButton(onClick = { editing = null; creating = true }) {
                        Icon(
                            imageVector = if (tab == Tab.VAULT) {
                                Icons.Outlined.Lock
                            } else {
                                Icons.Outlined.Add
                            },
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                if (tab == Tab.VAULT) R.string.notes_new_vault else R.string.notes_new_note,
                            ),
                        )
                    }
                }
            }
        }

        // ── list ───────────────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(
                if (tab == Tab.VAULT) R.string.notes_header_vault else R.string.notes_header_notes,
            ),
            footer = if (rows.isEmpty()) {
                stringResource(
                    if (tab == Tab.VAULT) R.string.notes_empty_vault else R.string.notes_empty_notes,
                )
            } else {
                null
            },
        ) {
            rows.forEachIndexed { index, row ->
                val expanded = revealId == row.id
                val shownBody = when {
                    row.isVault && expanded ->
                        store.readVault(row.id) ?: context.getString(R.string.notes_undecryptable)
                    row.isVault -> "••••••"
                    else -> row.body
                }
                SettingsRow(
                    title = row.title,
                    subtitle = shownBody.lineSequence().firstOrNull { it.isNotBlank() }?.take(140),
                    icon = if (row.isVault) Icons.Outlined.Lock else Icons.Outlined.Description,
                    iconColor = if (row.isVault) Color(0xFFFF9F0A) else Color(0xFF30B0C7),
                    onClick = {
                        if (row.isVault) {
                            revealId = if (expanded) null else row.id
                        } else {
                            editing = row
                            creating = false
                        }
                    },
                    showDivider = index < rows.lastIndex,
                )
                if (expanded) {
                    SettingsCardBlock {
                        Text(
                            text = shownBody,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MinisSmallOutlinedButton(onClick = { editing = row; creating = false }) {
                                Icon(Icons.Outlined.Edit, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.notes_edit))
                            }
                            MinisSmallOutlinedButton(onClick = { confirmDelete = row }) {
                                Icon(Icons.Outlined.Delete, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }

        message?.let { text ->
            SettingsSection {
                SettingsCardBlock {
                    Text(
                        text = text,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }

    confirmDelete?.let { target ->
        MinisAlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = stringResource(R.string.notes_delete_title),
            text = stringResource(R.string.notes_delete_message, target.title),
            confirmText = stringResource(R.string.delete),
            isDestructive = true,
            onConfirm = {
                scope.launch {
                    val ok = if (target.isVault) {
                        store.deleteVault(target.id, fromUi = true)
                    } else {
                        store.deleteNote(target.id)
                    }
                    message = context.getString(
                        if (ok) R.string.notes_deleted else R.string.notes_delete_failed,
                    )
                }
                confirmDelete = null
                resetForm()
            },
        )
    }
}

/** Header for the form: what is being created or edited, in the user's words. */
private fun editorHeader(editing: NoteStore.Note?, tab: Tab): Int = when {
    editing?.isVault == true -> R.string.notes_edit_vault
    editing != null -> R.string.notes_edit_note
    tab == Tab.VAULT -> R.string.notes_new_vault
    else -> R.string.notes_new_note
}

/** Which list the screen shows. The split IS the permission model. */
private enum class Tab(val label: Int) {
    NOTES(R.string.notes_tab_notes),
    VAULT(R.string.notes_tab_vault),
}
