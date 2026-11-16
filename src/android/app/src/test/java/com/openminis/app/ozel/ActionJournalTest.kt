package com.openminis.app.ozel

import com.openminis.app.data.journal.ActionJournal
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [MINIS-OZEL 11.3.1] The undo mechanism, tested where it actually breaks.
 *
 * The store/read half of the journal is bookkeeping; the half that can destroy
 * a user's file is [ActionJournal.undo]. Every case below is a way that could
 * go wrong, and each one is a decision the code has to make rather than a
 * behaviour that happens to fall out:
 *
 *  - a new file is undone by deleting it, but only if nobody touched it since;
 *  - an overwrite restores the exact previous bytes;
 *  - an append cuts exactly the appended region, not a byte more;
 *  - a file the agent did not write, but which changed afterwards, is left
 *    alone — undoing onto a later edit would destroy the later edit;
 *  - an entry with no payload reports "not reversible" instead of silently
 *    doing nothing.
 */
class ActionJournalTest {

    private val tmp: File = Files.createTempDirectory("journal-test").toFile()

    @Before
    fun setUp() {
        ActionJournal.testRoot = File(tmp, "journal")
    }

    @After
    fun tearDown() {
        ActionJournal.testRoot = null
        tmp.deleteRecursively()
    }

    private fun write(path: String, content: String): File {
        val f = File(tmp, path)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    @Test
    fun `undoing a newly created file deletes it`() {
        val f = write("new.txt", "hello")
        val entry = ActionJournal.record(
            ctx = null, tool = "file_write", title = "Write File", target = f.absolutePath,
            previous = null, afterBytes = f.length(),
        )
        assertEquals(ActionJournal.UndoKind.DELETE, entry.undoKind)

        val result = ActionJournal.undo(null, entry)

        assertTrue(result.message, result.ok)
        assertFalse(f.exists())
    }

    @Test
    fun `undoing an overwrite restores the previous bytes exactly`() {
        val before = "line one\nline two\n"
        val f = write("edit.txt", "replaced\n")
        val entry = ActionJournal.record(
            ctx = null, tool = "file_write", title = "Write File", target = f.absolutePath,
            previous = before, afterBytes = f.length(),
        )
        assertEquals(ActionJournal.UndoKind.RESTORE, entry.undoKind)

        val result = ActionJournal.undo(null, entry)

        assertTrue(result.message, result.ok)
        assertEquals(before, f.readText())
    }

    @Test
    fun `undoing an append cuts back to the previous length, not to zero`() {
        val original = "keep me\n"
        val f = write("append.txt", original + "and this too\n")
        val entry = ActionJournal.record(
            ctx = null, tool = "file_write", title = "Write File", target = f.absolutePath,
            previous = original, afterBytes = f.length(), appended = true,
        )
        assertEquals(ActionJournal.UndoKind.TRUNCATE, entry.undoKind)

        val result = ActionJournal.undo(null, entry)

        assertTrue(result.message, result.ok)
        assertEquals(original, f.readText())
    }

    @Test
    fun `a file that changed after the write is left alone`() {
        val f = write("changed.txt", "agent output")
        val entry = ActionJournal.record(
            ctx = null, tool = "file_write", title = "Write File", target = f.absolutePath,
            previous = null, afterBytes = f.length(),
        )
        // Someone else edits it afterwards.
        f.writeText("agent output plus a user's edit")

        val result = ActionJournal.undo(null, entry)

        assertFalse("must refuse rather than delete a changed file", result.ok)
        assertTrue(f.exists())
        assertTrue(f.readText().contains("user's edit"))
    }

    @Test
    fun `an oversized previous content is recorded as not reversible`() {
        val big = "x".repeat(ActionJournal.MAX_BLOB_BYTES + 1)
        val f = write("big.txt", "small")
        val entry = ActionJournal.record(
            ctx = null, tool = "file_write", title = "Write File", target = f.absolutePath,
            previous = big, afterBytes = f.length(),
        )

        assertEquals(ActionJournal.UndoKind.NONE, entry.undoKind)
        assertFalse(ActionJournal.undo(null, entry).ok)
    }

    @Test
    fun `a secret path never stores the previous content`() {
        val f = write("vault/entry.txt", "s3cr3t")
        val entry = ActionJournal.record(
            ctx = null, tool = "file_write", title = "Write File", target = f.absolutePath,
            previous = "old s3cr3t", afterBytes = f.length(),
        )

        assertEquals(ActionJournal.UndoKind.NONE, entry.undoKind)
        assertTrue(entry.blobPath == null)
        assertTrue(entry.note.contains("secret path"))
    }

    @Test
    fun `entries are listed newest first and can be found by prefix`() {
        val a = write("a.txt", "a")
        val b = write("b.txt", "b")
        ActionJournal.record(null, "file_write", "A", a.absolutePath, null, 1)
        ActionJournal.record(null, "file_write", "B", b.absolutePath, null, 1)

        val listed = ActionJournal.recent(null, limit = 10)

        assertEquals(2, listed.size)
        assertEquals("B", listed.first().title)
        assertNotNull(ActionJournal.find(null, listed.first().id.take(8)))
    }

    @Test
    fun `an entry cannot be undone twice`() {
        val f = write("once.txt", "x")
        val entry = ActionJournal.record(null, "file_write", "W", f.absolutePath, null, 1)
        assertTrue(ActionJournal.undo(null, entry).ok)

        val again = ActionJournal.undo(null, ActionJournal.find(null, entry.id)!!)

        assertFalse(again.ok)
        assertTrue(again.message.contains("already undone"))
    }

    @Test
    fun `summary names the undo state instead of hiding it`() {
        val f = write("s.txt", "x")
        ActionJournal.record(null, "file_write", "W", f.absolutePath, "old", 1)
        val text = ActionJournal.summarize(ActionJournal.recent(null, limit = 5))
        assertTrue(text, text.contains("can undo"))
    }
}
