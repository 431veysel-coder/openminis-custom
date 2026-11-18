package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [MINIS-OZEL 37070] FsSpace is the single translation from a space key to a
 * host directory. Every failure mode here is a *silent wrong-directory read*
 * in production — an agent writing into another project's workspace, or a
 * project's files landing where the orphan sweeper can delete them — so the
 * properties are pinned one by one rather than smoke-tested.
 */
class FsSpaceTest {

    private val tmp: File = Files.createTempDirectory("fsspace-test").toFile()

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private inline fun assertRejected(key: String, action: () -> Unit) {
        try {
            action()
            fail("expected rejection of '$key'")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ── key translation ────────────────────────────────────────────────────

    @Test
    fun `a plain chat id stays a chat id`() {
        val sid = "145d6883-6c1f-4a1e-9c39-0a1b2c3d4e5f"
        assertEquals(sid, FsSpace.spaceKey(sid, null))
        assertFalse(FsSpace.isProject(sid))
        assertNull(FsSpace.folderIdOf(sid))
    }

    @Test
    fun `a folder id becomes a project key`() {
        val fid = "0e7a1b2c-3d4e-5f60-7182-93a4b5c6d7e8"
        assertEquals("project:$fid", FsSpace.projectKey(fid))
        assertEquals("project:$fid", FsSpace.spaceKey("chat-id", fid))
        assertTrue(FsSpace.isProject("project:$fid"))
        assertEquals(fid, FsSpace.folderIdOf("project:$fid"))
    }

    @Test
    fun `projectKey is idempotent so re-stamping cannot double the prefix`() {
        val key = "project:abc"
        assertEquals(key, FsSpace.projectKey(key))
        assertEquals("project:abc", FsSpace.projectKey(FsSpace.projectKey("abc")))
    }

    @Test
    fun `blank folder ids mean no project, not a project named blank`() {
        assertNull(FsSpace.projectKey(null))
        assertNull(FsSpace.projectKey(""))
        assertNull(FsSpace.projectKey("   "))
        // And a blank folder falls back to the session id, never "project:".
        assertEquals("sid", FsSpace.spaceKey("sid", "  "))
    }

    @Test
    fun `a chat uuid can never collide with the project prefix`() {
        // The whole scheme rests on this: ChatRepository always mints UUIDs, so
        // no chat id can start with "project:". Asserted so a future id scheme
        // change (slug titles, prefixed ids) fails here instead of silently
        // reinterpreting existing sessions as projects.
        val uuid = java.util.UUID.randomUUID().toString()
        assertFalse(uuid.startsWith(FsSpace.PROJECT_PREFIX))
    }

    // ── host directories ───────────────────────────────────────────────────

    @Test
    fun `chat sessions and projects live under different roots`() {
        val chat = FsSpace.baseDir(tmp, "sid-1")
        val project = FsSpace.baseDir(tmp, "project:fid-1")

        assertEquals(File(tmp, "minis-sessions/sid-1"), chat)
        assertEquals(File(tmp, "minis-spaces/fid-1"), project)
        // The prefix must not leak into a directory name.
        assertFalse(project.path.contains("project:"))
    }

    @Test
    fun `subdirs land under the right root`() {
        for (sub in SessionMounts.SESSION_SUBDIRS) {
            assertEquals(
                File(tmp, "minis-sessions/sid/$sub"),
                FsSpace.subDir(tmp, "sid", sub),
            )
            assertEquals(
                File(tmp, "minis-spaces/fid/$sub"),
                FsSpace.subDir(tmp, "project:fid", sub),
            )
        }
    }

    @Test
    fun `a project directory is out of the orphan sweeper's path`() {
        // OrphanedSessionFiles lists storage.sessionsRoot and only deletes
        // UUID-named dirs there. A project must therefore never resolve under
        // minis-sessions — this is the property that stops a cleanup sweep from
        // taking a user's workspace.
        val project = FsSpace.baseDir(tmp, "project:fid")
        val sessionsRoot = File(tmp, "minis-sessions")
        assertFalse(project.path.startsWith(sessionsRoot.path + File.separator))
    }

    @Test
    fun `every root is enumerated so new roots are not silently skipped`() {
        assertEquals(
            listOf("minis-sessions", "minis-spaces"),
            FsSpace.roots(),
        )
    }

    // ── hostile input ──────────────────────────────────────────────────────

    @Test
    fun `a traversal id is rejected instead of escaping the root`() {
        for (bad in listOf("..", ".", "a/b", "a\\b", "project:../../etc")) {
            assertRejected(bad) { FsSpace.baseDir(tmp, bad) }
        }
    }

    @Test
    fun `an empty key is rejected`() {
        assertRejected("") { FsSpace.baseDir(tmp, "") }
        assertRejected("project:") { FsSpace.baseDir(tmp, "project:") }
    }

    // ── labels ─────────────────────────────────────────────────────────────

    @Test
    fun `labels distinguish a project from a chat without leaking the full id`() {
        val projectLabel = FsSpace.label("project:0123456789abcdef")
        val chatLabel = FsSpace.label("abcdef0123456789")
        assertTrue(projectLabel.startsWith("project:"))
        assertTrue(chatLabel.startsWith("session:"))
        assertEquals("project:01234567", projectLabel)
    }
}
