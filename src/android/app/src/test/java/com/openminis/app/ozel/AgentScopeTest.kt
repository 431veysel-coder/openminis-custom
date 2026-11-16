package com.openminis.app.ozel

import com.openminis.app.data.scope.AgentScope
import com.openminis.app.data.scope.AgentScope.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 11.3.1] The scope boundary, tested as a boundary.
 *
 * A path check that can be walked around with `..` is not a check, and a
 * command check that can be walked around with a redirect is not a check
 * either. Both are asserted here, along with the negative direction: the
 * common read-only commands must actually be allowed, or the mode is unusable
 * and the user turns it off.
 */
class AgentScopeTest {

    // ── paths ───────────────────────────────────────────────────────────────

    @Test
    fun `open mode allows any write`() {
        assertTrue(AgentScope.mayWrite(Mode.OPEN, "/system/build.prop"))
        assertTrue(AgentScope.mayWrite(Mode.OPEN, "/var/minis/workspace/a.txt"))
    }

    @Test
    fun `read-only mode refuses every write, including the workspace`() {
        assertFalse(AgentScope.mayWrite(Mode.READ_ONLY, "/var/minis/workspace/a.txt"))
        assertFalse(AgentScope.mayWrite(Mode.READ_ONLY, "/etc/hosts"))
    }

    @Test
    fun `workspace mode allows the workspace and refuses the rest`() {
        assertTrue(AgentScope.mayWrite(Mode.WORKSPACE, "/var/minis/workspace/a.txt"))
        assertTrue(AgentScope.mayWrite(Mode.WORKSPACE, "/var/minis/workspace/nested/deep/b.md"))
        assertTrue(AgentScope.mayWrite(Mode.WORKSPACE, "/tmp/scratch.sh"))
        assertFalse(AgentScope.mayWrite(Mode.WORKSPACE, "/etc/passwd"))
        assertFalse(AgentScope.mayWrite(Mode.WORKSPACE, "/var/minis/mounts/sdcard/x.txt"))
    }

    @Test
    fun `a path cannot escape the workspace with dot-dot`() {
        assertFalse(AgentScope.mayWrite(Mode.WORKSPACE, "/var/minis/workspace/../../etc/passwd"))
        assertFalse(AgentScope.mayWrite(Mode.WORKSPACE, "/var/minis/workspace/./../../system/x"))
        // and the harmless spelling still works
        assertTrue(AgentScope.mayWrite(Mode.WORKSPACE, "/var/minis/workspace/./notes/a.md"))
    }

    @Test
    fun `a sibling directory with the same prefix is not inside the workspace`() {
        // /var/minis/workspace-evil starts with /var/minis/workspace as a
        // STRING but is not under it. The first version of this check used
        // startsWith and let exactly this through.
        assertFalse(AgentScope.mayWrite(Mode.WORKSPACE, "/var/minis/workspace-evil/a.txt"))
    }

    @Test
    fun `normalize collapses redundant segments`() {
        assertEquals("/a/b", AgentScope.normalize("/a/./b"))
        assertEquals("/a", AgentScope.normalize("/a/b/.."))
        assertEquals("/", AgentScope.normalize("/../.."))
    }

    // ── shell ───────────────────────────────────────────────────────────────

    @Test
    fun `read-only commands are allowed in open and read-only modes`() {
        for (cmd in listOf("ls -la", "cat /etc/hosts", "rg -n foo src", "wc -l a.txt", "jq . x.json")) {
            assertTrue(cmd, AgentScope.mayRunShell(Mode.OPEN, cmd))
            assertTrue(cmd, AgentScope.mayRunShell(Mode.READ_ONLY, cmd))
        }
    }

    @Test
    fun `writers are refused in read-only mode`() {
        for (cmd in listOf("rm -rf /tmp/x", "mkdir -p a", "apt add jq", "mv a b", "tee out.txt")) {
            assertFalse(cmd, AgentScope.mayRunShell(Mode.READ_ONLY, cmd))
        }
    }

    @Test
    fun `a redirect makes an otherwise read-only command a writer`() {
        assertFalse(AgentScope.mayRunShell(Mode.READ_ONLY, "cat a.txt > b.txt"))
        assertFalse(AgentScope.mayRunShell(Mode.WORKSPACE, "ls >> list.txt"))
    }

    @Test
    fun `a pipeline is judged by every command in it`() {
        assertTrue(AgentScope.mayRunShell(Mode.READ_ONLY, "cat a.txt | grep foo | wc -l"))
        assertFalse(AgentScope.mayRunShell(Mode.READ_ONLY, "cat a.txt | tee b.txt"))
    }

    @Test
    fun `chaining hides a second command, so the chain is refused`() {
        assertFalse(AgentScope.mayRunShell(Mode.READ_ONLY, "ls; rm -rf /"))
        assertFalse(AgentScope.mayRunShell(Mode.READ_ONLY, "true && rm -rf /"))
    }

    @Test
    fun `command substitution is refused outright`() {
        // Written with ${'$'} so Kotlin's string templates stay out of the way.
        assertFalse(AgentScope.mayRunShell(Mode.READ_ONLY, "cat ${'$'}(rm -rf /)"))
        assertFalse(AgentScope.mayRunShell(Mode.READ_ONLY, "sudo rm -rf /tmp/x"))
    }

    @Test
    fun `mutating flags on a read-only verb are refused`() {
        assertFalse(AgentScope.mayRunShell(Mode.READ_ONLY, "find /tmp -name x -delete"))
    }

    @Test
    fun `open mode still allows everything`() {
        assertTrue(AgentScope.mayRunShell(Mode.OPEN, "rm -rf /tmp/x && echo done"))
    }

    @Test
    fun `refusals name the setting that changes them`() {
        assertTrue(AgentScope.writeRefusal(Mode.READ_ONLY, "/etc/x").contains("Agent scope"))
        assertTrue(AgentScope.shellRefusal(Mode.WORKSPACE, "rm x").contains("Agent scope"))
    }
}
