package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.journal.ActionJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [MINIS-OZEL 11.3.1] Settings › Minis Özel › Action journal.
 *
 * ## Why the user gets this screen and not just the agent
 *
 * The journal is a safety net, and a safety net nobody can see is a rumour. The
 * whole argument for letting an agent run unattended is that any single step can
 * be taken back afterwards — which only holds if the person can actually press
 * the button, not just the model.
 *
 * ## What it shows
 *
 * Newest first, one row per change: what tool, which file, when, and whether it
 * can still be undone. Rows that cannot be undone say why instead of hiding
 * (a file too large to snapshot, a secret path whose bytes were deliberately
 * not copied, a shell command with no inverse).
 *
 * Undo runs on [Dispatchers.IO]: it reads a payload and writes a file, which is
 * exactly the kind of work the code auditor's CODE001 rule exists to keep off
 * the main thread.
 */
@Composable
fun ActionJournalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<ActionJournal.Entry>>(emptyList()) }
    var storage by remember { mutableStateOf(0L) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    // Reads stay off the main thread for the same reason as undo; the list is
    // small but the day files are on disk.
    androidx.compose.runtime.LaunchedEffect(reload) {
        val (loaded, bytes) = withContext(Dispatchers.IO) {
            ActionJournal.recent(context, limit = 100) to ActionJournal.storageBytes(context)
        }
        entries = loaded
        storage = bytes
    }

    SettingsScaffold(
        title = stringResource(R.string.ozel_journal_title),
        onBack = onBack,
    ) {
        SettingsSection(footer = stringResource(R.string.ozel_journal_footer)) {
            SettingsCardBlock {
                Text(
                    text = stringResource(
                        R.string.ozel_journal_storage,
                        entries.size,
                        storage / 1024,
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message!!,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            SettingsSection {
                SettingsCardBlock {
                    Text(
                        text = stringResource(R.string.ozel_journal_empty),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }

        SettingsSection {
            entries.forEachIndexed { index, entry ->
                JournalRow(
                    entry = entry,
                    showDivider = index != entries.lastIndex,
                    onUndo = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ActionJournal.undo(context, entry)
                            }
                            message = result.message
                            reload++
                        }
                    },
                )
            }
        }

        if (entries.isNotEmpty()) {
            SettingsSection(footer = stringResource(R.string.ozel_journal_clear_footer)) {
                SettingsCardBlock {
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { ActionJournal.clear(context) }
                                message = null
                                reload++
                            }
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.ozel_journal_clear),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalRow(
    entry: ActionJournal.Entry,
    showDivider: Boolean,
    onUndo: () -> Unit,
) {
    val stamp = remember(entry.id) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
    }
    val undoLabel = when {
        entry.undone -> stringResource(R.string.ozel_journal_undone)
        entry.undoKind == ActionJournal.UndoKind.NONE -> stringResource(R.string.ozel_journal_no_undo)
        else -> null
    }

    SettingsRow(
        title = entry.title.ifBlank { entry.target },
        subtitle = buildString {
            append(stamp)
            append(" · ")
            append(entry.tool)
            if (entry.title.isNotBlank()) {
                append(" → ")
                append(entry.target)
            }
            if (undoLabel != null) {
                append(" · ")
                append(undoLabel)
            }
            if (entry.note.isNotBlank()) {
                append(" · ")
                append(entry.note)
            }
        },
        showDivider = showDivider,
        trailing = if (entry.undoKind != ActionJournal.UndoKind.NONE && !entry.undone) {
            {
                TextButton(onClick = onUndo) {
                    Icon(
                        Icons.Outlined.Restore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.ozel_journal_undo),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            null
        },
    )
}
