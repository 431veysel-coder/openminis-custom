package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ozel.ReplayStore
import com.openminis.app.replay.ReplayOutcome

/**
 * [37089] Settings › Minis X › Replay — record a chat, run it again.
 *
 * ## Why this is a screen and not a chat button
 *
 * Replay answers "did the model change help?" — a question asked rarely, by
 * someone deliberately comparing behaviour. A button in every chat would be a
 * button nobody uses and everybody wonders about. A settings screen is where a
 * measurement tool belongs.
 *
 * ## Why the recording is a copy, not a pointer
 *
 * A recording keeps the tool outputs frozen. If it pointed at the live chat,
 * the "frozen" half would be a lie the first time the chat was deleted or
 * edited — and the comparison would silently run against different data.
 *
 * ## Why a miss is shown as a number, not hidden
 *
 * A replay that falls back to live calls is not a replay. This screen therefore
 * prints the miss rate: it is the honest measure of "how differently did this
 * model behave", and the reason the runner refuses to call the network.
 */
@Composable
fun ReplayScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val recordings by ReplayStore.recordings.collectAsState()
    val outcomes by ReplayStore.outcomes.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.mx_replay_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.mx_replay_header),
            footer = stringResource(R.string.mx_replay_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.mx_replay_record_current),
                subtitle = stringResource(R.string.mx_replay_record_current_sub),
                onClick = { ReplayStore.recordCurrentChat(context) },
                showDivider = recordings.isNotEmpty(),
            )
        }

        if (recordings.isEmpty()) {
            SettingsCardBlock {
                Text(
                    stringResource(R.string.mx_replay_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                items(recordings, key = { it.id }) { rec ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            rec.prompt.take(60).ifBlank { rec.id },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${rec.modelId ?: "?"} · ${rec.originalTurns} tur · ${rec.entries.size} araç çağrısı",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                        outcomes[rec.id]?.let { out ->
                            Text(
                                out.compareWith(rec),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(onClick = {
                            selected = rec.id
                            ReplayStore.runReplay(context, rec.id)
                        }) {
                            Text(stringResource(R.string.mx_replay_run))
                        }
                    }
                }
            }
        }

        val last: ReplayOutcome? = selected?.let { outcomes[it] }
        if (last != null) {
            SettingsSection(header = stringResource(R.string.mx_replay_result)) {
                SettingsCardBlock {
                    Text(
                        moderateText(last),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        last.finalText.take(400),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun moderateText(o: ReplayOutcome): String = buildString {
    append("tur ${o.turns} · eşleşen ${o.hits} · ıskalama %${(o.missRate * 100).toInt()}")
    if (!o.completed) append(" · tamamlanmadı")
    if (o.misses.isNotEmpty()) {
        append("\nIskalanan çağrılar: ")
        append(o.misses.take(3).joinToString(", ") { it.tool })
    }
}
