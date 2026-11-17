package com.openminis.app.ozel

import com.openminis.app.agent.team.MemberResult
import com.openminis.app.agent.team.ProgressLedger
import com.openminis.app.agent.team.ProgressLedgerParser
import com.openminis.app.agent.team.StallTracker
import com.openminis.app.agent.team.TaskLedgerParser
import com.openminis.app.agent.team.TeamBudget
import com.openminis.app.agent.team.TeamEvent
import com.openminis.app.agent.team.TeamLoop
import com.openminis.app.agent.team.TeamOrchestrator
import com.openminis.app.agent.team.TeamOutcome
import com.openminis.app.agent.team.TeamWorkerRunner
import com.openminis.app.data.team.TeamMember
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 11.4 S2] The team loop, tested where it can be tested.
 *
 * The value of splitting the loop from the model call is exactly this file: the
 * control flow that decides "re-plan", "ask this member", "we are done" is
 * exercised against scripted replies, so a change to a threshold is caught here
 * and not after a run has spent the user's tokens. Every test below corresponds
 * to a failure that would be silent in production — a loop that never re-plans,
 * a run that reports success without verification, a ledger parser that accepts
 * prose.
 */
class TeamLoopTest {

    private val members = listOf(
        TeamMember(name = "Araştırmacı", role = "reads sources"),
        TeamMember(name = "Yazılımcı", role = "writes code"),
    )

    // ── Parser ─────────────────────────────────────────────────────────────

    private fun ledgerJson(
        satisfied: Boolean = false,
        progress: Boolean = true,
        inLoop: Boolean = false,
        instruction: String = "do the next step",
        speaker: String = "Araştırmacı",
    ): String = """
        {"is_request_satisfied":{"answer":$satisfied,"reason":"r"},
         "is_progress_being_made":{"answer":$progress,"reason":"r"},
         "is_in_loop":{"answer":$inLoop,"reason":"r"},
         "instruction_or_question":{"answer":"$instruction"},
         "next_speaker":{"answer":"$speaker"}}
    """.trimIndent()

    @Test
    fun `parser accepts a ledger wrapped in prose`() {
        val raw = "Here is my analysis:\n\n" + ledgerJson() + "\n\nLet me know."
        val parsed = ProgressLedgerParser.parse(raw, listOf("Araştırmacı", "Yazılımcı"))
        assertNotNull(parsed)
        assertEquals("Araştırmacı", parsed!!.nextSpeaker)
        assertEquals("do the next step", parsed.instruction)
    }

    @Test
    fun `parser accepts a fenced ledger`() {
        val raw = "```json\n" + ledgerJson(speaker = "Yazılımcı") + "\n```"
        assertEquals("Yazılımcı", ProgressLedgerParser.parse(raw, listOf("Araştırmacı", "Yazılımcı"))?.nextSpeaker)
    }

    @Test
    fun `parser tolerates a decorated speaker name`() {
        val raw = ledgerJson(speaker = "**Araştırmacı (reads sources)**")
        assertEquals(
            "Araştırmacı",
            ProgressLedgerParser.parse(raw, listOf("Araştırmacı", "Yazılımcı"))?.nextSpeaker,
        )
    }

    @Test
    fun `parser rejects prose instead of guessing`() {
        assertNull(ProgressLedgerParser.parse("I think we are nearly done here.", members.map { it.name }))
    }

    @Test
    fun `parser rejects a ledger naming a non-member`() {
        val raw = ledgerJson(speaker = "Muhasebeci")
        assertNull(ProgressLedgerParser.parse(raw, listOf("Araştırmacı", "Yazılımcı")))
    }

    @Test
    fun `parser rejects a ledger missing a key`() {
        val raw = """{"is_request_satisfied":{"answer":false,"reason":"x"}}"""
        assertNull(ProgressLedgerParser.parse(raw, listOf("Araştırmacı")))
    }

    @Test
    fun `satisfied ledger needs no speaker`() {
        val raw = ledgerJson(satisfied = true, speaker = "")
        val parsed = ProgressLedgerParser.parse(raw, listOf("Araştırmacı"))
        assertTrue(parsed!!.satisfied.answer)
        assertEquals("", parsed.nextSpeaker)
    }

    // ── Task ledger ────────────────────────────────────────────────────────

    @Test
    fun `task ledger drops a step assigned to an unknown member`() {
        val raw = """
            {"facts":["a"],"to_look_up":[],"to_derive":[],"guesses":[],
             "plan":[{"step":"real","assignee":"Araştırmacı"},
                     {"step":"ghost","assignee":"Muhasebeci"}]}
        """.trimIndent()
        val ledger = TaskLedgerParser.parse(raw, members.map { it.name })
        assertNotNull(ledger)
        assertEquals(listOf("real"), ledger!!.plan.map { it.step })
        assertEquals("Araştırmacı", ledger.plan.first().assignee)
    }

    // ── Stall rule ─────────────────────────────────────────────────────────

    private fun p(progress: Boolean = true, inLoop: Boolean = false) = ProgressLedger(
        satisfied = com.openminis.app.agent.team.LedgerAnswer(false, ""),
        progress = com.openminis.app.agent.team.LedgerAnswer(progress, ""),
        inLoop = com.openminis.app.agent.team.LedgerAnswer(inLoop, ""),
        instruction = "x",
        nextSpeaker = "Araştırmacı",
    )

    @Test
    fun `three unproductive turns in a row trigger a replan and reset the counter`() {
        val tracker = StallTracker(3)
        assertFalse(tracker.after(p(progress = false)))
        assertFalse(tracker.after(p(progress = false)))
        assertTrue(tracker.after(p(progress = false)))
        assertEquals(1, tracker.replans)
        assertEquals(3, tracker.lastDecisionCount)
        // Reset: the run gets three more chances, it is not permanently stuck.
        assertFalse(tracker.after(p(progress = false)))
    }

    @Test
    fun `a productive turn decrements instead of adding`() {
        val tracker = StallTracker(3)
        tracker.after(p(progress = false))
        tracker.after(p(progress = false))
        assertFalse(tracker.after(p()))
        // 2 → 1 after one good turn, so the next bad one is 2, not 3.
        assertFalse(tracker.after(p(progress = false)))
        assertEquals(2, tracker.lastDecisionCount)
    }

    @Test
    fun `looping counts as a stall`() {
        val tracker = StallTracker(2)
        assertFalse(tracker.after(p(inLoop = true)))
        assertTrue(tracker.after(p(inLoop = true)))
    }

    // ── The loop ───────────────────────────────────────────────────────────

    private class ScriptedOrchestrator(private val replies: MutableList<String>) : TeamOrchestrator {
        var calls = 0
        override suspend fun ask(prompt: String, json: Boolean): String {
            calls++
            return if (replies.isEmpty()) "no more scripted replies" else replies.removeAt(0)
        }
    }

    private class RecordingRunner : TeamWorkerRunner {
        val instructions = mutableListOf<String>()
        var result: String = "Araştırma tamam: /var/minis/workspace/rapor.md"
        override suspend fun run(member: TeamMember, instruction: String, round: Int): MemberResult {
            instructions += instruction
            return MemberResult(result)
        }
    }

    private val planJson = """
        {"facts":[],"to_look_up":["x"],"to_derive":[],"guesses":[],
         "plan":[{"step":"research","assignee":"Araştırmacı"},
                 {"step":"write","assignee":"Yazılımcı"}]}
    """.trimIndent()

    @Test
    fun `happy path runs a member then reports verified`() = runTest {
        val orchestrator = ScriptedOrchestrator(
            mutableListOf(
                planJson,                                  // task ledger
                ledgerJson(speaker = "Araştırmacı"),        // round 1 progress
                ledgerJson(satisfied = true, speaker = ""), // round 2 verdict
                """{"verified":true,"missing":[],"reason":"ok"}""", // verification
            ),
        )
        val runner = RecordingRunner()
        val outcome = TeamLoop(orchestrator, runner, TeamBudget(maxRounds = 6)).run(
            teamName = "Test", members = members, task = "yaz",
        )
        assertTrue(outcome.satisfied)
        assertTrue(outcome.verified)
        assertEquals(1, runner.instructions.size)
        assertEquals("Araştırmacı", outcome.events.filterIsInstance<TeamEvent.Asked>().first().speaker)
    }

    @Test
    fun `a verified claim is not trusted without the verification step`() = runTest {
        val orchestrator = ScriptedOrchestrator(
            mutableListOf(
                planJson,
                ledgerJson(speaker = "Araştırmacı"),
                ledgerJson(satisfied = true, speaker = ""),
                // The verifier disagrees with the orchestrator.
                """{"verified":false,"missing":["rapor.md yok"],"reason":"no file"}""",
                ledgerJson(speaker = "Araştırmacı"),
                ledgerJson(satisfied = true, speaker = ""),
                """{"verified":true,"missing":[],"reason":"ok"}""",
            ),
        )
        val runner = RecordingRunner()
        val outcome = TeamLoop(orchestrator, runner, TeamBudget(maxRounds = 10)).run(
            teamName = "Test", members = members, task = "yaz",
        )
        assertTrue(outcome.satisfied && outcome.verified)
        // The rejected claim led to a targeted turn that carries the gap.
        assertTrue(runner.instructions.any { it.contains("rapor.md yok") })
        assertEquals(1, outcome.events.filterIsInstance<TeamEvent.Verification>().count { !it.verified })
    }

    @Test
    fun `unreadable ledgers re-plan instead of looping forever`() = runTest {
        val replies = mutableListOf(planJson)
        // Every progress ledger is prose → 3 retries per round, all useless.
        repeat(40) { replies.add("I am not sure, let me think about it.") }
        val orchestrator = ScriptedOrchestrator(replies)
        val runner = RecordingRunner()
        val outcome = TeamLoop(orchestrator, runner, TeamBudget(maxRounds = 12, maxStalls = 3)).run(
            teamName = "Test", members = members, task = "yaz",
        )
        assertFalse(outcome.satisfied)
        assertTrue("expected at least one re-plan", outcome.replans >= 1)
        // A team that never produced a usable ledger must not run members on prose.
        assertTrue(runner.instructions.isEmpty())
    }

    @Test
    fun `round budget stops the run and says so`() = runTest {
        val replies = mutableListOf(planJson)
        repeat(30) { replies.add(ledgerJson(speaker = "Araştırmacı")) }
        val orchestrator = ScriptedOrchestrator(replies)
        val outcome = TeamLoop(orchestrator, RecordingRunner(), TeamBudget(maxRounds = 4)).run(
            teamName = "Test", members = members, task = "yaz",
        )
        assertEquals(TeamOutcome.StopReason.ROUNDS, outcome.stoppedBy)
        assertFalse(outcome.satisfied)
        assertEquals(4, outcome.rounds)
    }

    @Test
    fun `a team with no members refuses to run`() = runTest {
        val outcome = TeamLoop(ScriptedOrchestrator(mutableListOf()), RecordingRunner()).run(
            teamName = "Test", members = emptyList(), task = "yaz",
        )
        assertEquals(TeamOutcome.StopReason.NO_PLAN, outcome.stoppedBy)
        assertFalse(outcome.satisfied)
    }

    @Test
    fun `a plan with no steps refuses to run`() = runTest {
        val emptyPlan = """{"facts":[],"to_look_up":[],"to_derive":[],"guesses":[],"plan":[]}"""
        val outcome = TeamLoop(
            ScriptedOrchestrator(mutableListOf(emptyPlan, emptyPlan)), RecordingRunner(),
        ).run(teamName = "Test", members = members, task = "yaz")
        assertEquals(TeamOutcome.StopReason.NO_PLAN, outcome.stoppedBy)
    }
}
