package com.openminis.app.ozel

import com.openminis.app.agent.team.AskTeammateQuota
import com.openminis.app.agent.team.AskTeammateTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [MINIS-OZEL 11.5 · 37065] `ask_teammate` and its quota.
 *
 * The tool exists because 11.4's prompt promised it and the dispatcher did not
 * have it — a member that followed its own brief got `Unknown tool`. These tests
 * pin the contract the prompt now promises: resolvable names only, one answer,
 * bounded asking.
 */
class AskTeammateTest {

    private val peers = listOf("Ada", "Bor", "Cem")

    @Before
    fun setup() {
        AskTeammateQuota.clearAll()
    }

    @After
    fun teardown() {
        AskTeammateQuota.clearAll()
    }

    // ── Parsing ────────────────────────────────────────────────────────────

    @Test
    fun `a well formed question parses and keeps the canonical peer name`() {
        val call = AskTeammateTool.parse("""{"name":"ada","question":"Which API version?"}""", peers)
        assertNull(call.problem)
        // Canonical casing, because the peer is looked up by the name the roster
        // used — "ada" must not become a second, unknown member.
        assertEquals("Ada", call.name)
        assertEquals("Which API version?", call.question)
    }

    @Test
    fun `an unknown colleague is refused with the real roster`() {
        val call = AskTeammateTool.parse("""{"name":"Deniz","question":"x"}""", peers)
        assertNotNull(call.problem)
        assertTrue("the refusal must list who exists", call.problem!!.contains("Ada"))
        assertTrue(call.problem!!.contains("Deniz"))
    }

    @Test
    fun `missing fields and broken json are refused, not thrown`() {
        assertNotNull(AskTeammateTool.parse("""{"name":"Ada"}""", peers).problem)
        assertNotNull(AskTeammateTool.parse("""{"question":"x"}""", peers).problem)
        assertNotNull(AskTeammateTool.parse("not json", peers).problem)
    }

    @Test
    fun `a member cannot ask itself, because it is not in its own peer list`() {
        // The belt builds peers as "everyone except me"; the tool trusts that list.
        val call = AskTeammateTool.parse("""{"name":"Ada","question":"x"}""", listOf("Bor", "Cem"))
        assertNotNull(call.problem)
    }

    // ── Quota ──────────────────────────────────────────────────────────────

    @Test
    fun `three questions per round, eight per run`() {
        assertNull(AskTeammateTool.quotaRefusal(0, 0))
        assertNull(AskTeammateTool.quotaRefusal(2, 2))
        val round = AskTeammateTool.quotaRefusal(3, 3)
        assertNotNull(round)
        assertTrue("the refusal must offer the queue path", round!!.contains("questions"))

        val run = AskTeammateTool.quotaRefusal(0, 8)
        assertNotNull(run)
        assertTrue(run!!.contains("8"))
        // The two refusals are different problems and must read differently.
        assertFalse(round == run)
    }

    @Test
    fun `the same question twice costs one`() {
        assertTrue(AskTeammateQuota.noteQuestion("run_1", "Ada", "Bor", "which api version"))
        assertEquals(1, AskTeammateQuota.askedThisRound("run_1", "Ada"))
        assertFalse("the duplicate is refused", AskTeammateQuota.noteQuestion("run_1", "Ada", "Bor", "which api version"))
        assertEquals("and it is not counted", 1, AskTeammateQuota.askedThisRound("run_1", "Ada"))
        assertEquals(1, AskTeammateQuota.askedThisRun("run_1", "Ada"))
    }

    @Test
    fun `asking the same thing of a different colleague is a new question`() {
        assertTrue(AskTeammateQuota.noteQuestion("run_1", "Ada", "Bor", "which api version"))
        assertTrue(AskTeammateQuota.noteQuestion("run_1", "Ada", "Cem", "which api version"))
        assertEquals(2, AskTeammateQuota.askedThisRound("run_1", "Ada"))
    }

    @Test
    fun `a round reset clears the per-round count and the dedupe set but keeps the run total`() {
        AskTeammateQuota.noteQuestion("run_1", "Ada", "Bor", "q1")
        AskTeammateQuota.noteQuestion("run_1", "Ada", "Bor", "q2")
        AskTeammateQuota.resetRound("run_1")
        assertEquals(0, AskTeammateQuota.askedThisRound("run_1", "Ada"))
        assertEquals("the run total survives the round boundary", 2, AskTeammateQuota.askedThisRun("run_1", "Ada"))
        // The dedupe set is per round too, so the same question may be asked again
        // next round — deliberately: a round has new information in it.
        assertTrue(AskTeammateQuota.noteQuestion("run_1", "Ada", "Bor", "q1"))
    }

    @Test
    fun `counters are per run and per member`() {
        AskTeammateQuota.noteQuestion("run_1", "Ada", "Bor", "q")
        assertEquals(0, AskTeammateQuota.askedThisRound("run_2", "Ada"))
        assertEquals(0, AskTeammateQuota.askedThisRound("run_1", "Cem"))
    }

    @Test
    fun `forgetting a run drops its counters and leaves other runs alone`() {
        AskTeammateQuota.noteQuestion("run_1", "Ada", "Bor", "q")
        AskTeammateQuota.noteQuestion("run_2", "Ada", "Bor", "q")
        AskTeammateQuota.forgetRun("run_1")
        assertEquals(0, AskTeammateQuota.askedThisRun("run_1", "Ada"))
        assertEquals(1, AskTeammateQuota.askedThisRun("run_2", "Ada"))
    }

    // ── Prompt text ────────────────────────────────────────────────────────

    @Test
    fun `the peer prompt states the asker, the role and the no-tools limit`() {
        val prompt = AskTeammateTool.peerPrompt("Ada", "Which API version?", "engineer")
        assertTrue(prompt.contains("Ada"))
        assertTrue(prompt.contains("engineer"))
        assertTrue(prompt.contains("Which API version?"))
        // The limit must be in the prompt, not only in the token ceiling: a model
        // that knows the ceiling writes less in the first place.
        assertTrue(prompt.contains("no tools"))
    }

    @Test
    fun `a silent peer produces an instruction to proceed, not an error`() {
        // The asker is mid-turn; a peer that never answers must leave it able to
        // continue and to record the gap.
        val text = AskTeammateTool.noAnswer("Bor", "timed out")
        assertTrue(text.contains("Bor"))
        assertTrue(text.contains("timed out"))
        assertFalse(text.isEmpty())
    }

    @Test
    fun `an answer is labelled with who said it`() {
        val text = AskTeammateTool.result("Cem", "  v2.3  ")
        assertTrue(text.startsWith("Answer from Cem"))
        assertTrue(text.contains("v2.3"))
    }
}
