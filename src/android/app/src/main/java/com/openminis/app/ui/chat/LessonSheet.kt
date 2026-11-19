package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.agent.lesson.LessonProposal
import com.openminis.app.ozel.LessonRecorder
import kotlinx.coroutines.launch

/**
 * [37086] Ders kartı — ajanın kendi hatasından çıkardığı tek not.
 *
 * ## Neden onay istiyor
 *
 * Bir ajanın kendi hatasını "kural" olarak kaydetmesi, hatayı kalıcılaştırmanın
 * en hızlı yolu. Bu yüzden hafızaya yazan tek yol kullanıcının bu düğmesi;
 * `LessonRecorder` yalnız öneri üretiyor.
 *
 * ## Neden `MemoryTools` üzerinden yazmıyor
 *
 * O araç ajanın çağrısı için tasarlandı: argüman JSON'u, oturum kapsamı ve
 * araç sonucu bekliyor. Buradaki yazma kullanıcının doğrudan eylemi, o yüzden
 * `MemoryRepository` doğrudan kullanılıyor — ajanın aracını taklit etmek, iki
 * farklı güven seviyesini aynı kapıdan geçirmek olurdu.
 */
@Composable
fun LessonSheet(sessionId: String) {
    val context = LocalContext.current
    val proposals by LessonRecorder.proposal.collectAsState()
    val proposal: LessonProposal = proposals[sessionId] ?: return
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { LessonRecorder.dismiss(sessionId) },
        title = { Text("Bir ders çıkarayım mı?") },
        text = {
            Column {
                Text(
                    proposal.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Kanıt: " + proposal.evidence.take(2).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Onaylarsan hafızaya not olarak yazılır; sonraki sohbetlerde hatırlanır.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching {
                        // The personal memory log is this fork's "what I learned"
                        // surface, and the agent already reads it every turn — so
                        // a lesson written here is one the next run sees without
                        // any new plumbing.
                        val app = context.applicationContext as? com.openminis.app.MinisApp
                        app?.memoryRepository?.writeMemory("Ders: ${proposal.text}")
                    }
                    LessonRecorder.dismiss(sessionId)
                }
            }) { Text("Hafızaya yaz") }
        },
        dismissButton = {
            TextButton(onClick = { LessonRecorder.dismiss(sessionId) }) { Text("Gerek yok") }
        },
    )
}
