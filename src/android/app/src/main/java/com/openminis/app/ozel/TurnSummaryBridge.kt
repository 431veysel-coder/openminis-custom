package com.openminis.app.ozel

import android.content.Context
import com.openminis.app.data.OzelFeatures
import com.openminis.app.data.journal.ActionJournal
import com.openminis.app.ui.chat.diff.ChangeKind
import com.openminis.app.ui.chat.diff.FileChange
import com.openminis.app.ui.chat.diff.TurnDiff
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [37084] Tur özeti köprüsü — günlükten ("journal") insan için tek satıra.
 *
 * ## Neden yeni bir kayıt yolu yok
 *
 * `ActionJournal` zaten her dosya değişikliğini tutuyor ve ajan onu
 * okuyabiliyor. Eksik olan tek şey **kullanıcının** okuyabilmesi. Bu yüzden
 * burada ayrı bir günlük tutulmuyor; aynı kayıtlar tur başına filtreleniyor.
 *
 * ## Neden satır sayısı dosya başına tek
 *
 * "3 dosya değişti" yanıltıcı: biri 400 satır yazmışsa yeniden yazmadır, biri
 * tek karakter eklemişse düzeltmedir. Satır sayısı ikisini ayırır. Ama aynı
 * dosyaya üç kez yazıldıysa üç satır göstermek gürültü — dosya başına tek
 * satır, toplamda tek başlık.
 */
object TurnSummaryBridge {

    private val _latest = MutableStateFlow<Map<String, Pair<String, List<String>>>>(emptyMap())

    /** Oturum → (başlık, satırlar). Arayüz bunu bir sonraki turda gösterir. */
    val latest: StateFlow<Map<String, Pair<String, List<String>>>> = _latest.asStateFlow()

    /**
     * Tur sonunda çağrılır. [sinceMs] turun başlediği an.
     * Dönen değer `null` ise o turda hiçbir dosya değişmedi — satır gösterilmez.
     */
    fun summarizeTurn(ctx: Context, sessionId: String, sinceMs: Long): String? {
        if (!OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.TURN_DIFF)) return null
        val entries = runCatching {
            ActionJournal.recent(ctx, limit = 200, sessionId = sessionId)
                .filter { it.timestamp >= sinceMs && !it.undone }
        }.getOrDefault(emptyList())
        if (entries.isEmpty()) {
            _latest.value = _latest.value - sessionId
            return null
        }

        val changes = entries.mapIndexed { i, e ->
            FileChange(
                path = e.target,
                kind = when (e.undoKind) {
                    // Journal's DELETE means "the file did not exist before";
                    // undo removes it, so this write CREATED the file.
                    ActionJournal.UndoKind.DELETE -> ChangeKind.ADDED
                    else -> ChangeKind.MODIFIED
                },
                // No line counts: the journal stores sizes, not text, and this
                // bridge refuses to invent a (+120/−8) it never computed. The
                // headline therefore counts FILES, and the per-file lines show
                // the kind ("değişti", "yeni", "silindi"). Adding real counts
                // means extending ActionJournal.Entry — a separate change.
                added = 0,
                removed = 0,
                undoable = e.undoKind != ActionJournal.UndoKind.NONE,
                tool = e.tool,
                seq = i,
            )
        }
        val s = TurnDiff.summarize(changes)
        val headline = "${entries.map { it.target }.distinct().size} dosya değişti" +
            if (s.headline.contains("geri alınamayan")) " · geri alınamayan var" else ""
        val lines = s.lines
        _latest.value = _latest.value + (sessionId to (headline to lines))
        return headline
    }

    /** Kullanıcı özeti kapattı/geri aldı. */
    fun clear(sessionId: String) {
        _latest.value = _latest.value - sessionId
    }

    /** Bu turun geri alma planı — "turu geri al" düğmesi için. */
    fun undoPlanFor(ctx: Context, sessionId: String, sinceMs: Long): TurnDiff.UndoPlan {
        val entries = runCatching {
            ActionJournal.recent(ctx, limit = 200, sessionId = sessionId)
                .filter { it.timestamp >= sinceMs && !it.undone }
        }.getOrDefault(emptyList())
        return TurnDiff.undoPlan(
            entries.mapIndexed { i, e ->
                FileChange(
                    path = e.target,
                    kind = ChangeKind.MODIFIED,
                    undoable = e.undoKind != ActionJournal.UndoKind.NONE,
                    tool = e.tool,
                    seq = i,
                )
            },
        )
    }
}
