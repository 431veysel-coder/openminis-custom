package com.openminis.app.data.memory

import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.sandbox.FsSpace
import com.openminis.app.tools.MemoryTools
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 37073/37074] The whole point of the feature: two projects must
 * not see each other's memory, and the personal layer must stay readable
 * everywhere without becoming a dumping ground.
 *
 * These are the tests that would have caught the defect this feature exists to
 * fix — before 37070 every one of them returns the same single log.
 */
class MemoryLayerTest {

    private val filesDir: File = Files.createTempDirectory("memlayer").toFile()
    private val personal: MemoryRepository =
        MemoryRepository(File(filesDir, "minis-global/memory"))
    private lateinit var resolver: MemoryScopeResolver

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun newResolver(): MemoryScopeResolver {
        resolver = MemoryScopeResolver(filesDir, personal)
        return resolver
    }

    private fun projectId(n: Int) = "folder-$n"

    private fun write(tool: String, project: MemoryRepository?, projectName: String? = null): String {
        val out = MemoryTools.executeMemoryWrite(
            tool,
            personal = personal,
            project = project,
            projectName = projectName,
        )
        assertTrue("write failed: ${out.output}", out.success)
        return out.output
    }

    // ── routing ────────────────────────────────────────────────────────────

    @Test
    fun `outside a project everything still goes to the personal log`() {
        newResolver()
        val out = write("""{"tool_title":"t","content":"dil: Türkçe"}""", project = null)

        // No suffix is appended outside a project: the transcript stays exactly
        // as it was before 37070.
        assertFalse(out.contains("proje belleği"))
        assertFalse(out.contains("kişisel bellek"))
        assertTrue(personal.getMemory("Türkçe", "all").contains("Türkçe"))
    }

    @Test
    fun `inside a project the write lands in the project log by default`() {
        newResolver()
        val project = resolver.project(projectId(1), "Alpha", enabled = true)!!
        val out = write("""{"tool_title":"t","content":"karar: X"}""", project, "Alpha")

        assertTrue("the destination must be visible", out.contains("[proje belleği: Alpha]"))
        assertTrue(project.getMemory("karar", "daily").contains("karar: X"))
        // The personal log must be untouched — it holds no matching line at all.
        assertFalse(personal.getMemory("karar", "daily").contains("karar: X"))
    }

    @Test
    fun `scope=global writes to the personal log even inside a project`() {
        newResolver()
        val project = resolver.project(projectId(1), "Alpha", enabled = true)!!
        val out = write(
            """{"tool_title":"t","content":"kullanıcı adı: Veysel","scope":"global"}""",
            project,
            "Alpha",
        )

        assertTrue(out.contains("[kişisel bellek]"))
        assertTrue(personal.getMemory("Veysel", "daily").contains("Veysel"))
        assertFalse(project.getMemory("Veysel", "daily").contains("Veysel"))
    }

    @Test
    fun `two projects never see each other's memory`() {
        newResolver()
        val alpha = resolver.project(projectId(1), "Alpha", enabled = true)!!
        val beta = resolver.project(projectId(2), "Beta", enabled = true)!!

        write("""{"tool_title":"t","content":"alpha-only gizli not"}""", alpha, "Alpha")
        write("""{"tool_title":"t","content":"beta-only başka not"}""", beta, "Beta")

        // Each layer finds its own and nothing else.
        assertTrue(alpha.getMemory("alpha-only", "all").contains("alpha-only"))
        assertFalse(beta.getMemory("alpha-only", "all").contains("alpha-only"))
        assertTrue(beta.getMemory("beta-only", "all").contains("beta-only"))
        assertFalse(alpha.getMemory("beta-only", "all").contains("beta-only"))
    }

    @Test
    fun `memory_get all searches the project first and labels both layers`() {
        newResolver()
        val project = resolver.project(projectId(1), "Alpha", enabled = true)!!
        write("""{"tool_title":"t","content":"ortak kelime proje tarafı"}""", project, "Alpha")
        write("""{"tool_title":"t","content":"ortak kelime kişisel taraf","scope":"global"}""", project, "Alpha")

        val out = MemoryTools.executeMemoryGet(
            """{"tool_title":"t","keywords":"ortak","scope":"all"}""",
            personal = personal,
            project = project,
            projectName = "Alpha",
        )

        val text = out.output
        assertTrue("project header must name the project", text.contains("Project memory (PROJECT.md — Alpha"))
        assertTrue("personal header must be present", text.contains("Personal memory (GLOBAL.md)"))
        assertTrue(text.contains("proje tarafı"))
        assertTrue(text.contains("kişisel taraf"))
        // Project layer first: it is the more specific context.
        assertTrue(
            "project hits must precede personal hits",
            text.indexOf("proje tarafı") < text.indexOf("kişisel taraf"),
        )
    }

    @Test
    fun `memory_get scope=project does not leak the personal layer`() {
        newResolver()
        val project = resolver.project(projectId(1), "Alpha", enabled = true)!!
        write("""{"tool_title":"t","content":"sadece kisisel"}""", project = null)

        val out = MemoryTools.executeMemoryGet(
            """{"tool_title":"t","keywords":"sadece","scope":"project"}""",
            personal = personal,
            project = project,
            projectName = "Alpha",
        )
        assertFalse(out.output.contains("sadece kisisel"))
        assertFalse(out.output.contains("Personal memory"))
    }

    // ── disabled / label-only projects ─────────────────────────────────────

    @Test
    fun `a project with memory switched off yields no layer`() {
        newResolver()
        assertNull(resolver.project(projectId(1), "Alpha", enabled = false))
    }

    @Test
    fun `a null folder id never creates a project layer`() {
        newResolver()
        assertNull(resolver.project(null, null, enabled = true))
        assertNull(resolver.project("", "Alpha", enabled = true))
        assertNull(resolver.project("   ", "Alpha", enabled = true))
    }

    // ── storage layout ─────────────────────────────────────────────────────

    @Test
    fun `a project's memory lives beside its workspace, not in the personal dir`() {
        newResolver()
        val projectId = projectId(1)
        resolver.project(projectId, "Alpha", enabled = true)!!

        val dir = resolver.memoryDirFor(FsSpace.projectKey(projectId)!!)
        assertEquals(File(filesDir, "minis-spaces/$projectId/memory"), dir)
        assertFalse(
            "project memory must not sit under minis-global",
            dir.path.contains("minis-global"),
        )
    }

    @Test
    fun `the same project is one cached instance, different projects are not`() {
        newResolver()
        val a1 = resolver.project(projectId(1), "Alpha", enabled = true)
        val a2 = resolver.project(projectId(1), "Alpha renamed", enabled = true)
        val b = resolver.project(projectId(2), "Beta", enabled = true)

        assertTrue("same folder id must reuse the instance", a1 === a2)
        assertFalse(a1 === b)
        assertEquals(2, resolver.cachedCount())
    }

    @Test
    fun `forget drops a cached project so a deleted project stops being served`() {
        newResolver()
        resolver.project(projectId(1), "Alpha", enabled = true)
        assertEquals(1, resolver.cachedCount())
        resolver.forget(projectId(1))
        assertEquals(0, resolver.cachedCount())
    }

    // ── curated files ──────────────────────────────────────────────────────

    @Test
    fun `PROJECT_dot_md is a separate curated file with its own header`() {
        newResolver()
        val project = resolver.project(projectId(1), "Alpha", enabled = true)!!
        project.saveGlobalMd("Bu projede yanıtlar Türkçe ve kısa.\n")

        val fragment = project.loadProjectMemoryFragment("Alpha")
        assertNotNull(fragment)
        assertTrue(fragment!!.startsWith("Project memory (PROJECT.md — Alpha"))
        assertTrue(fragment.contains("THIS project only"))
        assertTrue(fragment.contains("yanıtlar Türkçe"))

        // And the personal file is a different file: writing one does not
        // populate the other.
        assertNull(personal.loadGlobalMemoryFragment())
        personal.saveGlobalMd("kullanıcı adı: Veysel\n")
        assertNotNull(personal.loadGlobalMemoryFragment())
        assertEquals(
            "kullanıcı adı: Veysel\n",
            personal.loadGlobalMd(),
        )
        assertEquals(
            "Bu projede yanıtlar Türkçe ve kısa.\n",
            project.loadGlobalMd(),
        )
    }

    @Test
    fun `the curated file name is what separates the two layers on disk`() {
        newResolver()
        val projectId = projectId(1)
        val project = resolver.project(projectId, "Alpha", enabled = true)!!
        project.saveGlobalMd("proje notu\n")
        personal.saveGlobalMd("kişisel not\n")

        val projectDir = resolver.memoryDirFor(FsSpace.projectKey(projectId)!!)
        val personalDir = File(filesDir, "minis-global/memory")
        assertEquals("proje notu\n", File(projectDir, MemoryRepository.PROJECT_FILE).readText())
        assertEquals("kişisel not\n", File(personalDir, MemoryRepository.GLOBAL_FILE).readText())
    }

    @Test
    fun `daily logs never collide because each layer has its own directory`() {
        newResolver()
        val project = resolver.project(projectId(1), "Alpha", enabled = true)!!
        write("""{"tool_title":"t","content":"proje günü"}""", project, "Alpha")
        write("""{"tool_title":"t","content":"kişisel gün","scope":"global"}""", project, "Alpha")

        // Same date, same filename, two different files.
        val projectLogs = resolver.memoryDirFor(FsSpace.projectKey(projectId(1))!!).listFiles()!!
        val personalLogs = File(filesDir, "minis-global/memory").listFiles()!!
        assertEquals(1, projectLogs.size)
        assertEquals(1, personalLogs.size)
        assertEquals(projectLogs[0].name, personalLogs[0].name)
        assertTrue(projectLogs[0].readText().contains("proje günü"))
        assertTrue(personalLogs[0].readText().contains("kişisel gün"))
    }
}
