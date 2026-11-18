package com.openminis.app.ui.chat.diff

/**
 * [OZEL 37084] Tur özeti — "bu turda ne değişti".
 *
 * ## Boşluk
 *
 * `JournalTool` her dosya değişikliğini ve geri alma tarifini tutuyor; ajan bunu
 * okuyabiliyor. Kullanıcı okuyamıyor. Sonuç: uzun bir turdan sonra ekranda on
 * iki araç bloğu var, "yani ne oldu" sorusunun cevabı yok.
 *
 * ## Neden sadece dosya sayısı değil
 *
 * "3 dosya değişti" cümlesi yanıltıcı: biri 400 satır silmişse bu bir yeniden
 * yazma, biri tek karakter eklemişse bu bir düzeltme. Satır sayısı ikisini
 * ayırır ve kullanıcıya "geri alayım mı" kararını verdirir.
 *
 * ## Neden geri alma burada bir düğme
 *
 * `ActionJournal.undo` tek kayıt alıyor. Tur düzeyinde geri alma, kayıtları
 * **yeniden eskiye** gezmek zorunda: dosya A yazılmış sonra düzenlenmişse önce
 * düzenlemeyi geri almak gerekir; ters sırada sadece ilk hâle döner ve aradaki
 * değişiklik nedensizce kaybolur. [undoPlan] bu sırayı üretir ve geri
 * alınamayanları ayırır — sessizce atlamaz.
 */
enum class ChangeKind { ADDED, MODIFIED, DELETED }

data class FileChange(
    val path: String,
    val kind: ChangeKind,
    val added: Int = 0,
    val removed: Int = 0,
    /** Journal ters kaydı var mı. */
    val undoable: Boolean = true,
    val tool: String = "",
    /** Aynı yolun birden çok değişikliğinde sıralama için. */
    val seq: Int = 0,
)

data class TurnSummary(
    val files: Int,
    val added: Int,
    val removed: Int,
    val addedFiles: Int,
    val removedFiles: Int,
    val modifiedFiles: Int,
    val undoableAll: Boolean,
    val headline: String,
    val lines: List<String>,
)

object TurnDiff {

    fun summarize(changes: List<FileChange>): TurnSummary {
        if (changes.isEmpty()) {
            return TurnSummary(0, 0, 0, 0, 0, 0, true, "Bu turda dosya değişmedi", emptyList())
        }
        val byPath = changes.groupBy { it.path }
        val added = changes.sumOf { it.added }
        val removed = changes.sumOf { it.removed }
        val addFiles = byPath.count { (_, v) -> v.last().kind == ChangeKind.ADDED }
        val delFiles = byPath.count { (_, v) -> v.last().kind == ChangeKind.DELETED }
        val modFiles = byPath.size - addFiles - delFiles
        val allUndoable = changes.all { it.undoable }

        val headline = buildString {
            append("${byPath.size} dosya")
            if (added > 0 || removed > 0) {
                append(" · ")
                if (added > 0) append("+$added")
                if (added > 0 && removed > 0) append(" ")
                if (removed > 0) append("−$removed")
                append(" satır")
            }
            if (!allUndoable) append(" · geri alınamayan değişiklik var")
        }

        val lines = byPath.entries
            .sortedByDescending { (_, v) -> maxOf(v.sumOf { it.added }, v.sumOf { it.removed }) }
            .take(6)
            .map { (path, v) ->
                val k = when (v.last().kind) {
                    ChangeKind.ADDED -> "yeni"
                    ChangeKind.MODIFIED -> "değişti"
                    ChangeKind.DELETED -> "silindi"
                }
                val a = v.sumOf { it.added }
                val r = v.sumOf { it.removed }
                "$k · ${shortPath(path)}" + if (a + r > 0) " (+$a/−$r)" else ""
            }

        return TurnSummary(
            files = byPath.size,
            added = added,
            removed = removed,
            addedFiles = addFiles,
            removedFiles = delFiles,
            modifiedFiles = modFiles,
            undoableAll = allUndoable,
            headline = headline,
            lines = lines,
        )
    }

    data class UndoStep(val path: String, val entries: List<FileChange>)

    data class UndoPlan(val steps: List<FileChange>, val skipped: List<Pair<String, String>>) {
        val canUndoAll: Boolean get() = skipped.isEmpty()
    }

    /**
     * Geri alma sırası: **en yeni önce**, dosya içinde de ters sıra.
     *
     * Geri alınamayanlar ayrı listede döner ve nedenleriyle birlikte — sessizce
     * atlanırsa tur "geri alındı" sanılır ama dosya yarı geri alınmış olur.
     */
    fun undoPlan(changes: List<FileChange>): UndoPlan {
        val skipped = changes.filter { !it.undoable }
            .map { it.path to "bu değişikliğin ters kaydı yok (${it.tool})" }
        val ordered = changes.filter { it.undoable }
            .sortedWith(compareByDescending<FileChange> { it.seq }.thenByDescending { it.path })
        return UndoPlan(ordered, skipped)
    }

    private fun shortPath(path: String): String {
        val marker = "/var/minis/"
        return if (path.contains(marker)) path.substringAfter(marker) else path
    }
}
