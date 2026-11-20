package com.openminis.app.data.memory

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [MINIS-OZEL 37072] The legacy memory move is the only part of the project
 * feature that can lose user data by itself, so its guarantees are pinned here
 * rather than checked by hand on a device:
 *
 *  - nothing is deleted before its copy is verified;
 *  - `SOUL.md` never moves (it is the persona, not work memory);
 *  - `GLOBAL.md` becomes `PROJECT.md` and the personal file keeps a pointer;
 *  - a second run is a no-op and never duplicates entries;
 *  - undo brings the notes back.
 */
class MemoryMigrationTest {

    private val root: File = Files.createTempDirectory("memmig").toFile()
    private val personal: File = File(root, "minis-global/memory")
    private val project: File = File(root, "minis-spaces/FID/memory")

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun seedPersonal(files: Map<String, String>) {
        personal.mkdirs()
        for ((name, body) in files) File(personal, name).writeText(body)
    }

    // ── plan ───────────────────────────────────────────────────────────────

    @Test
    fun `plan lists daily logs newest first and flags a non-empty GLOBAL`() {
        seedPersonal(
            mapOf(
                "GLOBAL.md" to "# kalıcı\n- dil: Türkçe\n",
                "2026-11-17.md" to "eski gün\n",
                "2026-11-18.md" to "yeni gün\n",
                "SOUL.md" to "ben Minis'im\n",
                "SOUL.icon.png" to "png",
                "bos.md" to "",
            ),
        )
        val plan = MemoryMigration.plan(personal)
        assertEquals(listOf("2026-11-18.md", "2026-11-17.md"), plan.dailyLogs.map { it.name })
        assertTrue(plan.hasGlobal)
        // Persona files are reported as skipped, not silently ignored.
        assertEquals(listOf("SOUL.md", "SOUL.icon.png"), plan.skipped.sorted())
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `an empty or missing dir plans nothing`() {
        assertTrue(MemoryMigration.plan(personal).isEmpty)
        personal.mkdirs()
        assertTrue(MemoryMigration.plan(personal).isEmpty)
        // A file that is empty is not memory.
        File(personal, "2026-11-18.md").writeText("")
        assertTrue(MemoryMigration.plan(personal).isEmpty)
    }

    // ── execute ────────────────────────────────────────────────────────────

    @Test
    fun `logs move, are byte-identical, and the source is gone`() {
        val body = "## karar\nproje hafızası ayrılsın\n"
        seedPersonal(mapOf("2026-11-18.md" to body))

        val plan = MemoryMigration.plan(personal)
        val result = MemoryMigration.execute(personal, project, "Geçmiş İşler", plan)

        assertEquals(1, result.movedLogs)
        val moved = File(project, "2026-11-18.md")
        assertTrue(moved.isFile)
        assertEquals(body, moved.readText())
        assertFalse(File(personal, "2026-11-18.md").exists())
        // The staging dir never survives a successful run.
        assertFalse(File(project, ".migration-stage").exists())
    }

    @Test
    fun `SOUL_dot_md is never moved`() {
        seedPersonal(
            mapOf(
                "SOUL.md" to "ben Minis'im\n",
                "2026-11-18.md" to "gün\n",
            ),
        )
        MemoryMigration.execute(personal, project, "P", MemoryMigration.plan(personal))

        assertTrue("SOUL.md must stay personal", File(personal, "SOUL.md").isFile)
        assertFalse("SOUL.md must not appear in the project", File(project, "SOUL.md").exists())
    }

    @Test
    fun `GLOBAL becomes PROJECT and the personal file keeps a pointer`() {
        val global = "# kalıcı\n- dil: Türkçe\n"
        seedPersonal(mapOf("GLOBAL.md" to global))

        val result = MemoryMigration.execute(personal, project, "Geçmiş İşler", MemoryMigration.plan(personal))

        assertTrue(result.projectFileWritten)
        assertTrue(result.globalPointerWritten)
        assertEquals(global, File(project, MemoryMigration.PROJECT_FILE).readText())

        val pointer = File(personal, "GLOBAL.md").readText()
        assertTrue("pointer must name the project", pointer.contains("Geçmiş İşler"))
        assertTrue("pointer must say what the file is for now", pointer.contains("kişisel"))
        assertFalse("the old body must not linger", pointer.contains("dil: Türkçe"))
    }

    @Test
    fun `an existing project log on the same date is appended to, never overlapped`() {
        seedPersonal(mapOf("2026-11-18.md" to "eski girdi\n"))
        project.mkdirs()
        File(project, "2026-11-18.md").writeText("yeni proje girdisi\n")

        MemoryMigration.execute(personal, project, "P", MemoryMigration.plan(personal))

        val merged = File(project, "2026-11-18.md").readText()
        assertTrue(merged.contains("eski girdi"))
        assertTrue(merged.contains("yeni proje girdisi"))
        // Project's own (later) entry stays on top, matching MemoryRepository's
        // newest-first prepend.
        assertTrue(merged.indexOf("yeni proje") < merged.indexOf("eski girdi"))
    }

    @Test
    fun `a second run over a migrated project writes no duplicate PROJECT file`() {
        seedPersonal(mapOf("GLOBAL.md" to "notlar\n", "2026-11-18.md" to "gün\n"))
        MemoryMigration.execute(personal, project, "P", MemoryMigration.plan(personal))

        // Re-plan: the personal dir now holds a pointer, so there is work again
        // unless the caller passes overwriteProjectFile = false.
        val plan2 = MemoryMigration.plan(personal)
        assertTrue("pointer still counts as content", plan2.hasGlobal)
        MemoryMigration.execute(personal, project, "P", plan2, overwriteProjectFile = false)

        // The project's PROJECT.md must still be the ORIGINAL notes, not the
        // pointer line that replaced GLOBAL.md.
        assertEquals("notlar\n", File(project, MemoryMigration.PROJECT_FILE).readText())
    }

    @Test
    fun `an idempotent re-run over nothing does no work`() {
        val result = MemoryMigration.execute(personal, project, "P", MemoryMigration.plan(personal))
        assertEquals(0, result.movedLogs)
        assertFalse(result.projectFileWritten)
        assertFalse(project.exists())
    }

    @Test
    fun `a destination that cannot be created fails loudly and leaves the sources`() {
        seedPersonal(mapOf("2026-11-18.md" to "gün\n"))
        // A regular file where the project dir must go.
        project.parentFile?.mkdirs()
        project.writeText("not a directory")

        try {
            MemoryMigration.execute(personal, project, "P", MemoryMigration.plan(personal))
            fail("expected the move to fail")
        } catch (_: IllegalArgumentException) {
            // expected: require(projectDir.isDirectory)
        }
        assertTrue("the source must survive a failed move", File(personal, "2026-11-18.md").isFile)
    }

    // ── undo ───────────────────────────────────────────────────────────────

    @Test
    fun `undo returns notes to the personal dir without dropping either side`() {
        seedPersonal(mapOf("GLOBAL.md" to "notlar\n", "2026-11-18.md" to "gün\n"))
        MemoryMigration.execute(personal, project, "P", MemoryMigration.plan(personal))

        val moved = MemoryMigration.undo(project, personal)
        assertEquals(2, moved)

        val global = File(personal, "GLOBAL.md").readText()
        assertTrue("project notes come back", global.contains("notlar"))
        assertTrue("the pointer is appended, not dropped", global.contains("kişisel"))
        assertTrue(File(personal, "2026-11-18.md").isFile)
    }

    @Test
    fun `undo of a project with no memory is a no-op`() {
        assertEquals(0, MemoryMigration.undo(project, personal))
    }

    // ── verification primitive ─────────────────────────────────────────────

    @Test
    fun `sameContent compares bytes, not names or lengths`() {
        val a = File(root, "a").apply { writeText("aynı") }
        val b = File(root, "b").apply { writeText("aynı") }
        val c = File(root, "c").apply { writeText("farklı") }
        assertTrue(MemoryMigration.sameContent(a, b))
        assertFalse(MemoryMigration.sameContent(a, c))
        assertFalse(MemoryMigration.sameContent(a, File(root, "missing")))
    }
}
