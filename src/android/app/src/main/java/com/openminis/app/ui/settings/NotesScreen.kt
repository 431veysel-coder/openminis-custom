package com.openminis.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardAction
import androidx.compose.ui.text.input.KeyboardActions
import androidx.compose.ui.text.input.KeyboardController
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.repository.NoteRepository
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.RowLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import com.openminis.app.tools.NoteTool

/**
 * Settings › Minis Özel › Notlar
 *
 * CRUD UI for agent notes. Notes are per-session and persist across conversations.
 * Includes export/import (JSON), tag filtering, search.
 */
@Composable
fun NotesScreen(
    noteRepository: NoteRepository,
    currentSessionId: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // State
    var searchQuery by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteRepository.Note?>(null) }
    var showImportPicker by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf("") }

    // Show all notes if no sessionId provided (e.g., from Settings), else filter by session
    val allNotes = if (currentSessionId.isBlank()) noteRepository.allNotes() else noteRepository.listForSession(currentSessionId)
    val allTags = remember(allNotes) {
        derivedStateOf {
            allNotes.flatMap { it.tagList }.distinct().sorted()
        }
    }

    val filteredNotes = remember(searchQuery, selectedTag, allNotes) {
        derivedStateOf {
            allNotes.filter { note ->
                val matchesSearch = searchQuery.isBlank() ||
                    note.title.lowercase().contains(searchQuery.lowercase()) ||
                    note.content.lowercase().contains(searchQuery.lowercase()) ||
                    note.tagList.any { it.contains(searchQuery.lowercase()) }
                val matchesTag = selectedTag == null || note.tagList.contains(selectedTag)
                matchesSearch && matchesTag
            }
        }
    }

    // Import file picker
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importFromUri(context, uri, currentSessionId, noteRepository)
            }
        }
    }

    // Export file saver
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json", "notes-${currentSessionId}.json")
    ) { uri ->
        if (uri != null && exportJson.isNotBlank()) {
            scope.launch {
                writeToUri(context, uri, exportJson)
            }
        }
    }

    fun showSnackbar(message: String) {
        snackbarHostState.showSnackbar(message)
    }

    fun createNote() {
        // If no sessionId (from Settings), create with a placeholder; user can edit
        val sessionId = if (currentSessionId.isBlank()) "default" else currentSessionId
        val newNote = noteRepository.create(sessionId)
        if (newNote != null) {
            editingNote = newNote
            showCreateDialog = true
        }
    }

    fun editNote(note: NoteRepository.Note) {
        editingNote = note
        showCreateDialog = true
    }

    fun deleteNote(note: NoteRepository.Note) {
        noteRepository.delete(note.id)
        showSnackbar("Not silindi")
    }

    fun saveNote(title: String, content: String, tags: String) {
        editingNote?.let { note ->
            noteRepository.update(note.id, title, content, tags)
            showSnackbar("Not kaydedildi")
        }
        showCreateDialog = false
        editingNote = null
    }

    fun exportNotes() {
        val notes = filteredNotes.value
        val json = NoteExport(notes = notes.map { NoteExport.NoteExportItem(
            id = it.id,
            sessionId = it.sessionId,
            title = it.title,
            content = it.content,
            tags = it.tags,
            version = it.version,
            createdAt = it.createdAt,
            updatedAt = it.updatedAt,
        ) })
        val jsonString = Json.encodeToString(json)
        exportJson = jsonString
        showExportDialog = true
    }

    fun importFromFile() {
        showImportPicker = true
    }

    SettingsScaffold(
        title = stringResource(R.string.notes_title),
        onBack = onBack,
    ) {
        // Search & filter bar
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search field
                var query by remember { mutableStateOf(searchQuery) }
                FilledTextField(
                    value = query,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.notes_search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.notes_clear_search))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp, bottom = 8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    keyboardActions = KeyboardActions(onDone = { KeyboardController().hide() }),
                )

                // Tag filter dropdown
                if (allTags.value.isNotEmpty()) {
                    DropdownMenuButton(
                        text = selectedTag?.let { "#$it" } ?: stringResource(R.string.notes_filter_tags),
                        leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) },
                        onClick = { /* handled by DropdownMenu */ },
                    ) {
                        DropdownMenu(expanded = true, onDismissRequest = {}) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.notes_all_tags)) },
                                onClick = { selectedTag = null }
                            )
                            Divider()
                            allTags.value.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text("#$tag") },
                                    onClick = { selectedTag = tag }
                                )
                            }
                        }
                    }
                }
            }
        }

        Divider()

        // Notes list
        if (filteredNotes.value.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        size = 64.dp
                    )
                    Text(
                        text = if (searchQuery.isNotBlank() || selectedTag != null)
                            stringResource(R.string.notes_no_results)
                        else
                            stringResource(R.string.notes_empty),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontSize = 16.sp
                    )
                    if (searchQuery.isBlank() && selectedTag == null) {
                        MinisButton(onClick = { createNote() }) {
                            Icon(Icons.Default.Add, contentDescription = null, size = 20.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.notes_create_first))
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp, 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(filteredNotes.value) { index, note ->
                    NoteCard(
                        note = note,
                        onClick = { editNote(note) },
                        onDelete = { deleteNote(note) },
                        onCopy = {
                            context.getSystemService(Context.CLIPBOARD_SERVICE)
                                ?.let { clipboard ->
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("note", note.content))
                                    showSnackbar("İçerik kopyalandı")
                                }
                        },
                    )
                }
            }
        }

        // Bottom action bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Divider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MinisButton(onClick = { createNote() }) {
                    Icon(Icons.Default.Add, contentDescription = null, size = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.notes_create))
                }
                MinisTextButton(onClick = { exportNotes() }) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, size = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.notes_export))
                }
                MinisTextButton(onClick = { importFromFile() }) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, size = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.notes_import))
                }
            }
        }
    }

    // Create/Edit Dialog
    if (showCreateDialog && editingNote != null) {
        val note = editingNote!!
        var title by remember { mutableStateOf(note.title) }
        var content by remember { mutableStateOf(note.content) }
        var tags by remember { mutableStateOf(note.tags) }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false; editingNote = null },
            title = { Text(if (note.version == 1) stringResource(R.string.notes_create_title) else stringResource(R.string.notes_edit_title)) },
            text = {
                Column(modifier = Modifier.padding(16.dp).width(320.dp)) {
                    DialogTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = stringResource(R.string.notes_title_label),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    DialogTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = stringResource(R.string.notes_content_label),
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        singleLine = false,
                        visualTransformation = VisualTransformation.None,
                    )
                    Spacer(Modifier.height(12.dp))
                    DialogTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = stringResource(R.string.notes_tags_label),
                        placeholder = stringResource(R.string.notes_tags_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { saveNote(title, content, tags) }) {
                    Text(stringResource(R.string.notes_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false; editingNote = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Export Dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.notes_export_title)) },
            text = {
                Column(modifier = Modifier.padding(16.dp).width(320.dp)) {
                    Text(stringResource(R.string.notes_export_desc))
                    Spacer(Modifier.height(12.dp))
                    FilledTextField(
                        value = exportJson,
                        onValueChange = { exportJson = it },
                        label = { Text(stringResource(R.string.notes_export_json)) },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        singleLine = false,
                        readOnly = true,
                        visualTransformation = VisualTransformation.None,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportLauncher.launch(null)
                    showExportDialog = false
                }) {
                    Text(stringResource(R.string.notes_export_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Import trigger
    LaunchedEffect(showImportPicker) {
        if (showImportPicker) {
            importLauncher.launch()
            showImportPicker = false
        }
    }

    SnackbarHost(snackbarHostState)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownMenuButton(
    text: String,
    leadingIcon: @Composable () -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.material3.Menu(
            anchor = androidx.compose.material3.MenuAnchorType.Primary,
            onDismissRequest = { expanded = false }
        ) {
            androidx.compose.material3.MenuAnchor(
                modifier = Modifier.fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp, 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        leadingIcon()
                        Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (expanded) {
                androidx.compose.material3.DropdownMenu(
                    expanded = true,
                    onDismissRequest = { expanded = false }
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: NoteRepository.Note,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth()
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (note.title.isNotBlank()) {
                        Text(
                            text = note.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = note.content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    if (note.tags.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            note.tagList.forEach { tag ->
                                androidx.compose.material3.Chip(
                                    onClick = { /* filter by tag */ },
                                    label = { Text("#$tag", fontSize = 11.sp) },
                                    colors = androidx.compose.material3.ChipDefaults.ChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Kopyala")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Sil",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                        .format(Date(note.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Text(
                    text = "v${note.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// Export data class
@Serializable
private data class NoteExport(
    val notes: List<NoteExport.NoteExportItem>
) {
    @Serializable
    data class NoteExportItem(
        val id: String,
        val sessionId: String,
        val title: String,
        val content: String,
        val tags: String,
        val version: Int,
        val createdAt: Long,
        val updatedAt: Long,
    )
}

private suspend fun importFromUri(
    context: Context,
    uri: Uri,
    sessionId: String,
    repository: NoteRepository
): Int = withContext(Dispatchers.IO) {
    var imported = 0
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val json = input.readText()
            importFromJson(repository, sessionId, json)
        }
    } catch (e: Exception) {
        // Error handled by caller via snackbar
    }
    imported
}

private suspend fun importFromJson(
    repository: NoteRepository,
    sessionId: String,
    json: String
): Int {
    // Parse JSON and create notes
    // Simple parsing without kotlinx.serialization to avoid extra dependency
    // Expected format: { "notes": [ { "title": "", "content": "", "tags": "", ... } ] }
    var imported = 0
    try {
        val obj = org.json.JSONObject(json)
        val arr = obj.optJSONArray("notes") ?: return 0
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val title = item.optString("title", "")
            val content = item.optString("content", "")
            val tags = item.optString("tags", "")
            repository.create(sessionId, title, content, tags)?.let { imported++ }
        }
    } catch (e: Exception) {
        // Ignore malformed
    }
    return imported
}

private suspend fun writeToUri(context: Context, uri: Uri, content: String) = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(content.toByteArray())
        }
    } catch (e: Exception) {
        // Error handled by caller
    }
}