package com.openminis.app.ozel

import com.openminis.app.data.verify.TaskChecks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [MINIS-OZEL 11.3.1] The definition of done, tested as a measurement.
 *
 * The point of the feature is that a claim becomes checkable, so these tests are
 * about the difference between "the file is missing" and "the file is there and
 * does not contain the string": those need different next steps, and an agent
 * that is told only "false" wastes the turn.
 */
class TaskChecksTest {

    private val tmp: File = Files.createTempDirectory("taskchecks").toFile()

    @After
    fun tearDown() {
        TaskChecks.testRoot = null
        tmp.deleteRecursively()
    }

    private fun write(name: String, content: String): File {
        val f = File(tmp, name)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    private fun check(kind: TaskChecks.Kind, path: String, expect: String = "") =
        TaskChecks.Check(id = "1", kind = kind, path = path, expectation = expect, note = "")

    @Test
    fun `file_exists passes for a non-empty file and fails for a missing one`() {
        val f = write("a.txt", "content")
        assertTrue(TaskChecks.evaluate(check(TaskChecks.Kind.FILE_EXISTS, f.absolutePath)).passed)

        val missing = TaskChecks.evaluate(check(TaskChecks.Kind.FILE_EXISTS, File(tmp, "no.txt").absolutePath))
        assertFalse(missing.passed)
        assertEquals("no such file", missing.detail)
    }

    @Test
    fun `an empty file does not count as done output`() {
        val f = write("empty.txt", "")
        val outcome = TaskChecks.evaluate(check(TaskChecks.Kind.FILE_EXISTS, f.absolutePath))
        assertFalse("an empty file usually means the write failed silently", outcome.passed)
        assertTrue(outcome.detail.contains("empty"))
    }

    @Test
    fun `file_contains distinguishes missing file from missing text`() {
        val f = write("b.txt", "hello world")
        assertTrue(TaskChecks.evaluate(check(TaskChecks.Kind.FILE_CONTAINS, f.absolutePath, "world")).passed)

        val wrongText = TaskChecks.evaluate(check(TaskChecks.Kind.FILE_CONTAINS, f.absolutePath, "absent"))
        assertFalse(wrongText.passed)
        assertTrue(wrongText.detail.contains("not found"))

        val wrongPath = TaskChecks.evaluate(check(TaskChecks.Kind.FILE_CONTAINS, File(tmp, "no.txt").absolutePath, "x"))
        assertEquals("no such file", wrongPath.detail)
    }

    @Test
    fun `file_matches evaluates a multiline regular expression`() {
        val f = write("c.txt", "alpha\nbeta\ngamma\n")
        assertTrue(TaskChecks.evaluate(check(TaskChecks.Kind.FILE_MATCHES, f.absolutePath, "^beta$")).passed)
        assertFalse(TaskChecks.evaluate(check(TaskChecks.Kind.FILE_MATCHES, f.absolutePath, "^delta$")).passed)
    }

    @Test
    fun `an invalid regular expression fails with the reason, it does not throw`() {
        val f = write("d.txt", "x")
        val outcome = TaskChecks.evaluate(check(TaskChecks.Kind.FILE_MATCHES, f.absolutePath, "[unclosed"))
        assertFalse(outcome.passed)
        assertTrue(outcome.detail.contains("invalid pattern"))
    }

    @Test
    fun `json_has_key parses and checks the key`() {
        val f = write("e.json", "{\"version\": 3, \"name\": \"x\"}")
        assertTrue(TaskChecks.evaluate(check(TaskChecks.Kind.JSON_HAS_KEY, f.absolutePath, "version")).passed)
        assertFalse(TaskChecks.evaluate(check(TaskChecks.Kind.JSON_HAS_KEY, f.absolutePath, "absent")).passed)

        val bad = write("f.json", "not json")
        val outcome = TaskChecks.evaluate(check(TaskChecks.Kind.JSON_HAS_KEY, bad.absolutePath, "x"))
        assertTrue(outcome.detail.contains("not valid JSON"))
    }

    @Test
    fun `a criterion without an expectation fails instead of silently passing`() {
        val f = write("g.txt", "anything")
        assertFalse(TaskChecks.evaluate(check(TaskChecks.Kind.FILE_CONTAINS, f.absolutePath, "")).passed)
    }

    @Test
    fun `the report states the count and names every failure`() {
        val ok = write("ok.txt", "fine")
        val report = TaskChecks.runAll(
            listOf(
                check(TaskChecks.Kind.FILE_EXISTS, ok.absolutePath),
                check(TaskChecks.Kind.FILE_EXISTS, File(tmp, "gone.txt").absolutePath),
            ),
        )
        assertFalse(report.allPassed)
        assertEquals(1, report.failed.size)
        val text = report.render()
        assertTrue(text.contains("1 of 2"))
        assertTrue(text.contains("FAIL"))
        assertTrue(text.contains("PASS"))
    }

    @Test
    fun `an empty criterion set is not a pass`() {
        val report = TaskChecks.runAll(emptyList())
        assertFalse(report.allPassed)
        assertTrue(report.render().contains("nothing was verified"))
    }

    @Test
    fun `criteria survive a save and load round trip, and can be cleared`() {
        TaskChecks.testRoot = File(tmp, "verify")
        val f = write("h.txt", "x")
        TaskChecks.save(
            null, "session-a",
            listOf(
                check(TaskChecks.Kind.FILE_EXISTS, f.absolutePath),
                check(TaskChecks.Kind.FILE_CONTAINS, f.absolutePath, "x"),
            ),
        )

        val loaded = TaskChecks.load(null, "session-a")
        assertEquals(2, loaded.size)
        assertEquals(TaskChecks.Kind.FILE_CONTAINS, loaded[1].kind)

        // A different session does not see another's criteria.
        assertTrue(TaskChecks.load(null, "session-b").isEmpty())

        TaskChecks.clear(null, "session-a")
        assertTrue(TaskChecks.load(null, "session-a").isEmpty())
    }
}
