package com.openminis.app.ui.team

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.team.CouncilLiveRegistry
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.team.SessionCouncil
import com.openminis.app.data.team.Team
import com.openminis.app.data.team.TeamStore
import com.openminis.app.ui.settings.SettingsRow
import kotlinx.coroutines.launch

/**
 * [37129 · A1] **Sohbetin konseyi** — üst bardaki simge.
 *
 * ## Neden üst bar, neden Ekip ekranı yetmiyor
 *
 * Konsey, sohbetin **bağlamında** bir eylemdir: "bu konuşmayı bir heyete
 * danış". Kullanıcıyı ekran değiştirmeye zorlamak "sohbetten erişim" fikrini
 * boşa çıkarır — o zaman zaten Ekip ekranından ulaşılırdı.
 *
 * ## Üç durum, tek simge
 *
 * | Durum | Görünüm | Dokununca |
 * |---|---|---|
 * | `NONE` | soluk | takım seçme listesi |
 * | `IDLE` | birincil renk | takım bilgisi + [Değiştir] + [Kaldır] |
 * | `RUNNING` | hata rengi | **canlı koşu ekranı** |
 *
 * Durum `SessionCouncil.presence` ile hesaplanır (saf, testli): "koşuyor"
 * göstergesi gerçekten bir koşu varken yanar. Yanlış durum göstermek, bu
 * depoda kınanan türden bir yalan.
 *
 * ## Neden koşu kimliği `CouncilRunRegistry`'den değil `teamId`'den
 *
 * `CouncilRunRegistry` iptal tutamağı tutar (`.48`) ve koşu bitince **temizlenir**.
 * Ekranın ihtiyacı "şu an bu takım için canlı bir koşu var mı"; onu
 * `CouncilLiveRegistry` söyler ve koşu bitince de kaydı bir süre kalır.
 *
 * ## Bilerek yapmadığı
 *
 * Raporu **sohbete mesaj olarak yazmıyor**. Sohbete asistan mesajı eklemenin
 * bugün tek public yolu `sendMessage` (kullanıcı mesajı) ve onu ajan çıktısı
 * için kullanmak transkripti bozar. Rapor diske ve koşu ekranına yazılıyor;
 * sohbete geri akıtılması ayrı bir tasarım kararı.
 */
@Composable
fun ChatCouncilButton(
    sessionId: String,
    chatRepository: ChatRepository,
    /** Live run screen for a run id. */
    onOpenRun: (String) -> Unit,
) {
    val context = LocalContext.current
    val runs by CouncilLiveRegistry.runs.collectAsState()

    var resolvedTeamId by remember(sessionId) { mutableStateOf<String?>(null) }
    var ownTeamId by remember(sessionId) { mutableStateOf<String?>(null) }
    var inherited by remember(sessionId) { mutableStateOf(false) }
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var sheet by remember(sessionId) { mutableStateOf(false) }
    // After a change the binding is re-read from the database — never kept as a
    // second in-memory truth next to the row that owns it.
    var stamp by remember(sessionId) { mutableStateOf(0) }

    LaunchedEffect(sessionId, stamp) {
        resolvedTeamId = runCatching { chatRepository.councilForSession(sessionId) }.getOrNull()
        ownTeamId = runCatching { chatRepository.councilOwnTeam(sessionId) }.getOrNull()
        inherited = runCatching { chatRepository.councilIsInherited(sessionId) }.getOrDefault(false)
        loaded = true
    }

    if (!loaded) return

    val team: Team? = remember(resolvedTeamId) {
        resolvedTeamId?.let { id ->
            runCatching { TeamStore.list(context) }
                .getOrDefault(emptyList())
                .firstOrNull { it.id == id }
        }
    }
    val liveRun = runs.values.firstOrNull {
        it.running && resolvedTeamId != null && it.teamId == resolvedTeamId
    }
    val presence = SessionCouncil.presence(resolvedTeamId, liveRun?.teamId)

    IconButton(
        onClick = {
            if (presence == SessionCouncil.Presence.RUNNING) {
                liveRun?.let { onOpenRun(it.runId) }
            } else {
                sheet = true
            }
        },
    ) {
        Icon(
            imageVector = Icons.Outlined.Groups,
            contentDescription = stringResource(
                when (presence) {
                    SessionCouncil.Presence.NONE -> R.string.chat_council_none
                    SessionCouncil.Presence.IDLE -> R.string.chat_council_idle
                    SessionCouncil.Presence.RUNNING -> R.string.chat_council_running
                },
            ),
            tint = when (presence) {
                SessionCouncil.Presence.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                SessionCouncil.Presence.IDLE -> MaterialTheme.colorScheme.primary
                SessionCouncil.Presence.RUNNING -> MaterialTheme.colorScheme.error
            },
        )
    }

    if (sheet) {
        CouncilSheet(
            sessionId = sessionId,
            team = team,
            inherited = inherited,
            hasOwnBinding = ownTeamId != null,
            chatRepository = chatRepository,
            onDismiss = { sheet = false },
            onChanged = {
                sheet = false
                stamp++
            },
        )
    }
}

/**
 * Takım seç veya göster.
 *
 * "Kaldır" **yalnız bu sohbetin kendi bağı varsa** görünür ve `null` yazar —
 * o da sohbeti **proje varsayılanına döndürür**, "konsey yok" demek değil.
 * Bu ayrım metinde yazılı, çünkü ikisi çok farklı şeyler ve kullanıcı
 * "kaldırdım" deyip hâlâ bir heyete bağlı kalmayı beklemiyor.
 */
@Composable
private fun CouncilSheet(
    sessionId: String,
    team: Team?,
    inherited: Boolean,
    hasOwnBinding: Boolean,
    chatRepository: ChatRepository,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val teams = remember(sessionId) {
        runCatching { TeamStore.list(context) }.getOrDefault(emptyList())
    }
    var picking by remember(sessionId) { mutableStateOf(team == null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(team?.name ?: stringResource(R.string.chat_council_pick_title)) },
        text = {
            Column {
                if (team != null && !picking) {
                    Text(
                        stringResource(
                            if (inherited) R.string.chat_council_from_project
                            else R.string.chat_council_own,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    team.members.forEach { m ->
                        val role = m.role.takeIf { it.isNotBlank() }
                        Text(
                            if (role == null) "• " + m.name else "• " + m.name + " · " + role,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    TextButton(
                        onClick = { picking = true },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.chat_council_change))
                    }
                } else if (teams.isEmpty()) {
                    Text(stringResource(R.string.chat_council_no_teams))
                } else {
                    teams.forEach { t ->
                        SettingsRow(
                            title = t.name,
                            subtitle = stringResource(R.string.chat_council_member_count, t.members.size),
                            onClick = {
                                scope.launch {
                                    chatRepository.setCouncilForSession(sessionId, t.id)
                                    onChanged()
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (team != null && !picking && hasOwnBinding) {
                TextButton(onClick = {
                    scope.launch {
                        chatRepository.setCouncilForSession(sessionId, null)
                        onChanged()
                    }
                }) {
                    Text(
                        stringResource(R.string.chat_council_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
