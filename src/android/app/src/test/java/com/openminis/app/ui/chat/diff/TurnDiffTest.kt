package com.openminis.app.ui.chat.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OZEL 37084] Tur özeti testleri. */
class TurnDiffTest {

    private fun ch(path: String, kind: ChangeKind, a: Int = 0, r: Int = 0, undo: Boolean = true, seq: Int = 0) =
        FileChange(path, kind, a, r, undo, "file_edit", seq)

    @Test
    fun `bos tur bos ozet uretir`() {
        val s = TurnDiff.summarize(emptyList())
        assertEquals(0, s.files)
        assertEquals("Bu turda dosya değişmedi", s.headline)
    }

    @Test
    fun `ayni dosyanin iki degisikligi tek dosya sayilir`() {
        // "3 dosya değişti" yanıltıcı: aynı dosya 3 kez düzenlenmiş olabilir.
        val s = TurnDiff.summarize(
            listOf(
                ch("/var/minis/workspace/a.kt", ChangeKind.ADDED, a = 10, seq = 1),
                ch("/var/minis/workspace/a.kt", ChangeKind.MODIFIED, a = 2, r = 1, seq = 2),
            ),
        )
        assertEquals(1, s.files)
        assertEquals(12, s.added)
        assertEquals(1, s.removed)
        // Son hâli MODIFIED olduğu için dosya "değişti" sayılır, "yeni" değil.
        assertEquals(0, s.addedFiles)
        assertEquals(1, s.modifiedFiles)
    }

    @Test
    fun `silinen dosya ayri sayilir`() {
        val s = TurnDiff.summarize(listOf(ch("/x/a", ChangeKind.DELETED, r = 40)))
        assertEquals(1, s.removedFiles)
        assertEquals(0, s.addedFiles)
        assertTrue(s.headline.contains("−40"))
    }

    @Test
    fun `geri alinamayan degisiklik baslikta gorunur`() {
        val s = TurnDiff.summarize(listOf(ch("/storage/a.txt", ChangeKind.MODIFIED, a = 1, undo = false)))
        assertFalse(s.undoableAll)
        assertTrue(s.headline.contains("geri alınamayan"))
    }

    @Test
    fun `geri alma sirasi en yeniden eskiye`() {
        // KARAR: önce son düzenleme geri alınır; ters sırada aradaki
        // değişiklik nedensizce kaybolur.
        val plan = TurnDiff.undoPlan(
            listOf(
                ch("/var/minis/workspace/a.kt", ChangeKind.ADDED, a = 10, seq = 1),
                ch("/var/minis/workspace/a.kt", ChangeKind.MODIFIED, a = 2, seq = 2),
                ch("/var/minis/workspace/b.kt", ChangeKind.ADDED, a = 5, seq = 3),
            ),
        )
        assertEquals(listOf("/var/minis/workspace/b.kt", "/var/minis/workspace/a.kt", "/var/minis/workspace/a.kt"),
            plan.steps.map { it.path })
        assertTrue(plan.canUndoAll)
    }

    @Test
    fun `geri alinamayan adim sessizce atlanmaz`() {
        val plan = TurnDiff.undoPlan(
            listOf(
                ch("/var/minis/workspace/a.kt", ChangeKind.MODIFIED, seq = 1),
                ch("/var/minis/mounts/ro/a.kt", ChangeKind.MODIFIED, undo = false, seq = 2),
            ),
        )
        assertEquals(1, plan.steps.size)
        assertEquals(1, plan.skipped.size)
        assertFalse(plan.canUndoAll)
        assertTrue(plan.skipped.single().second.contains("ters kaydı yok"))
    }

    @Test
    fun `ozet satiri en cok degisen dosyayla baslar`() {
        val s = TurnDiff.summarize(
            listOf(
                ch("/var/minis/workspace/kucuk.kt", ChangeKind.MODIFIED, a = 1, seq = 1),
                ch("/var/minis/workspace/buyuk.kt", ChangeKind.ADDED, a = 400, seq = 2),
            ),
        )
        assertTrue(s.lines.first().contains("buyuk.kt"))
        assertEquals(2, s.lines.size)
    }
}
