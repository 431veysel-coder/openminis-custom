package com.openminis.app.ozel

import com.openminis.app.sandbox.ToolchainProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL] Tests for the toolchain probe, the fork's first blocking tests.
 *
 * These exist because of a bug that was invisible in every other way: the
 * screen classified a group by a single binary, so installing `jq` through the
 * free-form field turned the nine-tool "shell utilities" group green. The app
 * compiled, the screen rendered, nothing crashed — it just lied. A test is the
 * only thing that can catch that class of mistake.
 */
class ToolchainProbeTest {

    private val devtools = ToolchainProbe.Spec(
        groupId = "devtools",
        binaries = listOf("jq", "sqlite3", "openssl", "rg", "tmux"),
    )
    private val python = ToolchainProbe.Spec(groupId = "python", binaries = listOf("python3", "note-a"))

    @Test
    fun `every binary present is Present`() {
        val out = listOf("jq", "sqlite3", "openssl", "rg", "tmux")
            .joinToString("\n") { "devtools:$it=yes" }

        val result = ToolchainProbe.parse(out, listOf(devtools))

        assertEquals(ToolchainProbe.Presence.Present, result["devtools"])
        assertTrue(ToolchainProbe.isComplete(result["devtools"]))
    }

    @Test
    fun `one missing binary is Partial and names it`() {
        // The regression: jq present, the other eight absent.
        val out = """
            devtools:jq=yes
            devtools:sqlite3=no
            devtools:openssl=no
            devtools:rg=no
            devtools:tmux=no
        """.trimIndent()

        val result = ToolchainProbe.parse(out, listOf(devtools))

        val presence = result["devtools"]
        assertTrue("expected Partial, got $presence", presence is ToolchainProbe.Presence.Partial)
        assertEquals(
            listOf("sqlite3", "openssl", "rg", "tmux"),
            (presence as ToolchainProbe.Presence.Partial).missing,
        )
        assertFalse(ToolchainProbe.isComplete(presence))
    }

    @Test
    fun `nothing present is Absent, not Partial`() {
        val out = listOf("jq", "sqlite3", "openssl", "rg", "tmux")
            .joinToString("\n") { "devtools:$it=no" }

        assertEquals(
            ToolchainProbe.Presence.Absent,
            ToolchainProbe.parse(out, listOf(devtools))["devtools"],
        )
    }

    @Test
    fun `a group the script never reported is Absent, never Present`() {
        // Silence means the probe did not run. Treating that as "installed" is
        // how a broken shell turns the whole screen green.
        val result = ToolchainProbe.parse("", listOf(devtools, python))

        assertEquals(ToolchainProbe.Presence.Absent, result["devtools"])
        assertEquals(ToolchainProbe.Presence.Absent, result["python"])
    }

    @Test
    fun `partial output for one group does not affect another`() {
        val out = """
            devtools:jq=yes
            python:python3=yes
            python:note-a=no
        """.trimIndent()

        val result = ToolchainProbe.parse(out, listOf(devtools, python))

        assertTrue(result["devtools"] is ToolchainProbe.Presence.Partial)
        assertTrue(result["python"] is ToolchainProbe.Presence.Partial)
        assertEquals(
            listOf("note-a"),
            (result["python"] as ToolchainProbe.Presence.Partial).missing,
        )
    }

    @Test
    fun `quoted and whitespace-padded lines are still parsed`() {
        // The shell may echo with quotes depending on how the branch is taken;
        // a stricter parser would silently drop those lines and report Absent.
        val out = "  'devtools:jq=yes'  \n\"devtools:sqlite3=yes\"\ndevtools:openssl=yes\n" +
            "devtools:rg=yes\ndevtools:tmux=yes\n"

        assertEquals(
            ToolchainProbe.Presence.Present,
            ToolchainProbe.parse(out, listOf(devtools))["devtools"],
        )
    }

    @Test
    fun `malformed lines are ignored rather than guessed`() {
        val out = """
            devtools:jq=yes
            garbage-without-separator
            devtools:=yes
            devtools:openssl=maybe
            devtools:sqlite3=yes
            devtools:rg=yes
            devtools:tmux=yes
        """.trimIndent()

        // openssl's line says "maybe": an unreadable answer is not "present",
        // so openssl must show up as missing rather than be assumed installed.
        val presence = ToolchainProbe.parse(out, listOf(devtools))["devtools"]
        assertTrue(presence is ToolchainProbe.Presence.Partial)
        assertEquals(
            listOf("openssl"),
            (presence as ToolchainProbe.Presence.Partial).missing,
        )
    }

    @Test
    fun `buildScript emits one line per binary and nothing else`() {
        val script = ToolchainProbe.buildScript(listOf(python))

        assertTrue(script.contains("command -v python3"))
        assertTrue(script.contains("command -v note-a"))
        // Every branch must print, or parse() sees silence and says Absent.
        assertEquals(2, Regex("echo '").findAll(script).count() / 2)
    }

    @Test
    fun `buildScript output round-trips through parse`() {
        // Simulates the shell printing "yes" for every binary.
        val script = ToolchainProbe.buildScript(listOf(devtools))
        val simulated = Regex("""echo '([a-z0-9]+):([a-z0-9]+)=yes'""")
            .findAll(script)
            .map { "${it.groupValues[1]}:${it.groupValues[2]}=yes" }
            .joinToString("\n")

        assertEquals(
            ToolchainProbe.Presence.Present,
            ToolchainProbe.parse(simulated, listOf(devtools))["devtools"],
        )
    }
}
