package com.openminis.app.ozel

import com.openminis.app.agent.team.TerminalTakeover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 11.5 · 37063] The terminal takeover state machine.
 *
 * Every case here is a way two writers end up on one PTY. That is the failure the
 * class exists to prevent, and it is silent when it happens: both commands run,
 * one command's output is attributed to the other, and neither transcript records
 * the mix-up.
 */
class TerminalTakeoverTest {

    private var now = 1_000L
    private fun takeover() = TerminalTakeover { now }

    @Test
    fun `an idle shell is granted at once`() {
        val t = takeover()
        assertEquals(TerminalTakeover.State.USER, t.request(agentBusy = false))
        assertFalse("the agent must not run while the user owns the shell", t.agentMayRun())
        assertTrue(t.agentRefusal().isNotBlank())
    }

    @Test
    fun `a request during a command waits instead of pre-empting it`() {
        val t = takeover()
        assertEquals(TerminalTakeover.State.REQUESTED, t.request(agentBusy = true))
        assertTrue("still parked while the command runs", t.snapshot(agentBusy = true).waitingForCommand)
        assertFalse(t.agentMayRun())
        // The refusal must name the wait, not a missing capability.
        assertTrue(t.agentRefusal().contains("current command"))
    }

    @Test
    fun `the parked request is granted when the command finishes, exactly once`() {
        val t = takeover()
        t.request(agentBusy = true)
        assertTrue("first call grants", t.onAgentCommandFinished())
        assertEquals(TerminalTakeover.State.USER, t.snapshot(agentBusy = false).state)
        assertFalse("a second finish must not re-grant", t.onAgentCommandFinished())
    }

    @Test
    fun `the agent may run again only after the user releases`() {
        val t = takeover()
        t.request(agentBusy = false)
        assertTrue(t.release())
        assertTrue(t.agentMayRun())
        assertEquals("", t.agentRefusal())
        // Idempotent: a screen disposing twice must not throw or toggle state.
        assertFalse(t.release())
        assertTrue(t.agentMayRun())
    }

    @Test
    fun `a double tap is one request`() {
        val t = takeover()
        t.request(agentBusy = true)
        now = 2_000L
        t.request(agentBusy = true)
        // The timestamp must not reset: the screen's "waiting" label is driven by
        // it, and a resetting timestamp would make the wait look eternal.
        assertEquals(1_000L, t.snapshot(agentBusy = true).requestedAtMs)
    }

    @Test
    fun `a request is not granted by a command that was never running`() {
        val t = takeover()
        t.request(agentBusy = false) // granted immediately
        assertFalse("already granted — no second signal is owed", t.onAgentCommandFinished())
        assertEquals(TerminalTakeover.State.USER, t.snapshot(agentBusy = false).state)
    }

    @Test
    fun `stale parking is detectable so a screen can warn`() {
        val t = takeover()
        t.request(agentBusy = true)
        assertFalse(t.isStalePark(agentBusy = true, staleMs = 60_000L))
        now = 200_000L
        assertTrue(t.isStalePark(agentBusy = true, staleMs = 60_000L))
        // Not stale if the agent is no longer busy: it is about to be granted.
        assertFalse(t.isStalePark(agentBusy = false, staleMs = 60_000L))
    }

    @Test
    fun `the button label tells the truth in all three states`() {
        val t = takeover()
        assertEquals("Ele geçir", t.label(agentBusy = false))
        t.request(agentBusy = true)
        assertEquals("Komut bitince…", t.label(agentBusy = true))
        t.onAgentCommandFinished()
        assertEquals("Bırak", t.label(agentBusy = false))
    }

    @Test
    fun `the user state refuses the agent without pretending the tool is missing`() {
        val t = takeover()
        t.request(agentBusy = false)
        val refusal = t.agentRefusal()
        // Must not read as "you do not have shell_execute": the member does. A
        // wrong reason produces a retry, and the retry hits the same wall.
        assertFalse(refusal.isEmpty())
        assertTrue(refusal.contains("user"))
        // And the AGENT state has no refusal at all, so an empty string here
        // would have meant "nothing is wrong" while nothing was right.
        assertEquals("", takeover().agentRefusal())
    }
}
