package com.openminis.app.ui.projects

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.SessionBriefRow
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * [MINIS-OZEL 11.4] One project: the conversations filed under it.
 *
 * The three screens this slice ships are deliberately read-through views of
 * data the app already owns (groups, membership, titles, timestamps). Project
 * settings — working directory, capability set, default model/team — attach to
 * this same screen as further sections, which is why the header is the group's
 * own name rather than a generic title.
 */
@Composable
fun ProjectDetailScreen(
    folderId: String,
    chatRepository: ChatRepository,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    var folder by remember(folderId) { mutableStateOf<FolderEntity?>(null) }
    var sessions by remember(folderId) { mutableStateOf<List<SessionBriefRow>?>(null) }
    // [37126] İçeri sohbet taşımanın yolu yoktu: proje ekranı açılıyor, boş
    // görünüyor, eklenecek bir şey sunulmuyordu. Taşıma yalnız Sohbetler
    // ekranının grup menüsünde vardı.
    var adding by remember { mutableStateOf(false) }
    var unfiled by remember { mutableStateOf<List<SessionBriefRow>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(folderId, reload) {
        folder = chatRepository.getFolder(folderId)
        sessions = chatRepository.sessionBriefsInFolder(folderId)
    }

    SettingsScaffold(
        title = folder?.name ?: stringResource(R.string.projects_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
        ) {
            SettingsSection(
                header = stringResource(R.string.projects_sessions_header),
                footer = folder?.description,
            ) {
                SettingsRow(
                    title = stringResource(R.string.projects_add_session),
                    icon = Icons.Outlined.Add,
                    onClick = {
                        adding = true
                        scope.launch {
                            // Diyalog açılırken yükle: liste boşsa "eklenecek
                            // sohbet yok" demek zorundayız, boş bir kutu değil.
                            unfiled = chatRepository.unfiledSessionBriefs()
                        }
                    },
                    showDivider = true,
                )
                val rows = sessions
                when {
                    rows == null -> SettingsRow(
                        title = stringResource(R.string.projects_loading),
                        showDivider = false,
                    )
                    rows.isEmpty() -> SettingsRow(
                        title = stringResource(R.string.projects_no_sessions_title),
                        subtitle = stringResource(R.string.projects_no_sessions_subtitle),
                        showDivider = false,
                    )
                    else -> rows.forEachIndexed { index, row ->
                        SettingsRow(
                            title = row.title?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.projects_session_untitled),
                            subtitle = coarseTime(context, row.updatedAt),
                            icon = Icons.Outlined.ChatBubbleOutline,
                            onClick = { onOpenSession(row.id) },
                            showDivider = index < rows.lastIndex,
                        )
                    }
                }
            }
            if (adding) {
                AlertDialog(
                    onDismissRequest = { adding = false },
                    title = { Text(stringResource(R.string.projects_add_session)) },
                    text = {
                        if (unfiled.isEmpty()) {
                            Text(stringResource(R.string.projects_add_none))
                        } else {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                unfiled.forEach { row ->
                                    TextButton(onClick = {
                                        adding = false
                                        scope.launch {
                                            // Tek çağrı, tek liste: kısmi taşıma
                                            // olmasın diye dizi hâlinde.
                                            chatRepository.setFolderForSessions(
                                                folderId,
                                                listOf(row.id),
                                            )
                                            reload++
                                        }
                                    }) {
                                        Text(
                                            row.title?.takeIf { it.isNotBlank() }
                                                ?: stringResource(R.string.projects_session_untitled),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { adding = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }
            Text(
                text = stringResource(R.string.projects_detail_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp),
            )
        }
    }
}

/**
 * Deliberately coarse ("3 hr ago", "yesterday", "5/11"): a project row answers
 * "which conversation was I just in", not "at what minute". Reuses the session
 * list's own string resources so the two screens never disagree about wording.
 */
private fun coarseTime(context: android.content.Context, timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    if (minutes < 1) return context.getString(R.string.time_just_now)
    if (minutes < 60) return context.getString(R.string.time_minutes_ago, minutes.toInt())
    if (hours < 24) return context.getString(R.string.time_hours_ago, hours.toInt())

    val date = Calendar.getInstance().apply { time = Date(timestamp) }
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (date.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        date.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    ) {
        return context.getString(R.string.time_yesterday)
    }
    return "${date.get(Calendar.MONTH) + 1}/${date.get(Calendar.DAY_OF_MONTH)}"
}
