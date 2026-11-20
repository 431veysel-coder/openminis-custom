package com.openminis.app.ozel

import com.openminis.app.agent.team.AssignTaskTool
import com.openminis.app.agent.team.EvidenceKind
import com.openminis.app.agent.team.TaskBoard
import com.openminis.app.agent.team.TaskEvidence
import com.openminis.app.agent.team.TaskState
import com.openminis.app.agent.team.TaskTools
import com.openminis.app.agent.team.TeamTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 11.5 · 37050–37051] The task board's rules.
 *
 * The board is the part of the council a user reads AFTER the run, so its rules
 * have to hold without a model in the loop. The one that matters most is the
 * evidence rule: "done" without proof is how a task list becomes fiction.
 */
class TaskBoardTest {

    private fun task(
        id: String = "t_1",
        title: String = "check the API",
        state: TaskState = TaskState.OPEN,
        owner: String = "Ada",
        evidence: List<TaskEvidence> = emptyList(),
    ) = TeamTask(id = id, title = title, state = state, owner = owner, evidence = evidence)

    // ── The evidence rule ──────────────────────────────────────────────────

    @Test
    fun `a task with no evidence cannot be marked done`() {
        val board = TaskBoard(listOf(task()))
        val (next, updated, refusal) = board.moveTo("t_1", TaskState.DONE)
        assertTrue("the move must be refused", refusal.isNotEmpty())
        assertNull(updated)
        // And nothing changed: a refused move must not half-apply.
        assertEquals(TaskState.OPEN, next.find("t_1")!!.state)
        assertEquals(board, next)
    }

    @Test
    fun `an empty evidence row is not evidence`() {
        val board = TaskBoard(listOf(task(evidence = listOf(TaskEvidence(EvidenceKind.OUTPUT, "")))))
        val (_, updated, refusal) = board.moveTo("t_1", TaskState.DONE)
        // The failure mode this prevents: a model with nothing to show emits
        // {"kind":"output","value":""} and the board accepts it.
        assertNull(updated)
        assertTrue(refusal.isNotEmpty())
    }

    @Test
    fun `evidence of any kind satisfies the rule`() {
        for (evidence in listOf(
            TaskEvidence(EvidenceKind.PATH, "/var/minis/workspace/report.md"),
            TaskEvidence(EvidenceKind.URL, "https://example.com/x"),
            TaskEvidence(EvidenceKind.OUTPUT, "3 passed"),
        )) {
            val board = TaskBoard(listOf(task(evidence = listOf(evidence))))
            val (next, updated, refusal) = board.moveTo("t_1", TaskState.DONE)
            assertEquals("", refusal)
            assertNotNull(updated)
            assertEquals(TaskState.DONE, next.find("t_1")!!.state)
        }
    }

    @Test
    fun `unusable evidence is refused at attach time, not silently stored`() {
        val board = TaskBoard(listOf(task()))
        val (next, refusal) = board.addEvidence("t_1", TaskEvidence(EvidenceKind.OUTPUT, "   "))
        assertTrue(refusal.isNotEmpty())
        assertTrue(next.find("t_1")!!.evidence.isEmpty())
    }

    // ── Dropping ───────────────────────────────────────────────────────────

    @Test
    fun `a dropped task needs a reason`() {
        val board = TaskBoard(listOf(task()))
        val (_, updated, refusal) = board.moveTo("t_1", TaskState.DROPPED)
        assertNull(updated)
        assertTrue(refusal.isNotEmpty())

        val (after, dropped, ok) = board.moveTo("t_1", TaskState.DROPPED, reason = "the API is gone")
        assertEquals("", ok)
        assertEquals(TaskState.DROPPED, after.find("t_1")!!.state)
        // The reason becomes the detail, so the board carries it rather than
        // losing it in a log.
        assertTrue(dropped!!.detail.contains("API is gone"))
    }

    // ── Adding ─────────────────────────────────────────────────────────────

    @Test
    fun `a blank title is refused`() {
        val (next, stored, refusal) = TaskBoard().add(TeamTask(id = "t_1", title = "  "))
        assertNull(stored)
        assertTrue(refusal.isNotEmpty())
        assertTrue(next.tasks.isEmpty())
    }

    @Test
    fun `titles and details are trimmed and capped`() {
        // Must EXCEED the cap: 500 characters is under MAX_DETAIL (600), so the
        // old expectation (that the length equals the cap) could never hold.
        val long = "x".repeat(TaskBoard.MAX_DETAIL + 250)
        val (board, stored, _) = TaskBoard().add(TeamTask(id = "t_1", title = "  spot  ", detail = long))
        assertEquals("spot", stored!!.title)
        assertEquals(TaskBoard.MAX_DETAIL, stored.detail.length)
        assertEquals(1, board.tasks.size)
    }

    @Test
    fun `the board has a ceiling`() {
        var board = TaskBoard()
        repeat(TaskBoard.MAX_TASKS) { i ->
            val (next, _, refusal) = board.add(TeamTask(id = "t_$i", title = "task $i"))
            assertEquals("", refusal)
            board = next
        }
        val (after, stored, refusal) = board.add(TeamTask(id = "t_over", title = "one more"))
        assertNull(stored)
        assertTrue(refusal.isNotEmpty())
        assertEquals(TaskBoard.MAX_TASKS, after.tasks.size)
    }

    // ── Queries ────────────────────────────────────────────────────────────

    @Test
    fun `mine returns only open and doing tasks of that owner`() {
        val board = TaskBoard(
            listOf(
                task(id = "t_1", owner = "Ada"),
                task(id = "t_2", owner = "Ada", state = TaskState.DOING),
                task(id = "t_3", owner = "Ada", state = TaskState.DONE,
                    evidence = listOf(TaskEvidence(EvidenceKind.URL, "https://x"))),
                task(id = "t_4", owner = "Bor"),
            ),
        )
        assertEquals(listOf("t_1", "t_2"), board.mine("Ada").map { it.id })
        assertEquals(listOf("t_4"), board.mine("bor").map { it.id })
        assertEquals(4, board.tasks.size)
    }

    @Test
    fun `counts cover every state including the empty ones`() {
        val board = TaskBoard(listOf(task(), task(id = "t_2", state = TaskState.DOING)))
        val counts = board.counts()
        assertEquals(1, counts[TaskState.OPEN])
        assertEquals(1, counts[TaskState.DOING])
        assertEquals(0, counts[TaskState.DONE])
        assertEquals(0, counts[TaskState.DROPPED])
    }

    @Test
    fun `the prompt block is deterministic and bounded`() {
        val tasks = (1..12).map { task(id = "t_$it", title = "task $it") }
        val board = TaskBoard(tasks)
        val a = board.promptBlock("Ada", limit = 5)
        val b = board.promptBlock("Ada", limit = 5)
        // Two runs of the same board must produce the same prompt, or the budget
        // accounting and the tests both become meaningless.
        assertEquals(a, b)
        assertTrue(a.contains("task 1"))
        assertTrue(a.contains("+7 more"))
        assertEquals("", board.promptBlock("Nobody"))
    }

    // ── Id resolution ──────────────────────────────────────────────────────

    @Test
    fun `a short id resolves by prefix`() {
        val board = TaskBoard(listOf(task(id = "t_ab12cd34")))
        assertNotNull(TaskTools.resolve(board, "t_ab12").task)
        assertNull(TaskTools.resolve(board, "t_ab12").problem)
    }

    @Test
    fun `an ambiguous prefix is refused rather than guessed`() {
        val board = TaskBoard(
            listOf(task(id = "t_ab12cd34"), task(id = "t_ab12ffff")),
        )
        val resolved = TaskTools.resolve(board, "t_ab12")
        assertNull(resolved.task)
        assertTrue(resolved.problem!!.contains("2 tasks"))
    }

    @Test
    fun `an unknown id names the tool that shows the real ones`() {
        val resolved = TaskTools.resolve(TaskBoard(), "nope")
        assertTrue(resolved.problem!!.contains("task_list"))
    }

    // ── Assignment ─────────────────────────────────────────────────────────

    @Test
    fun `a member cannot assign work to itself`() {
        val call = AssignTaskTool.parse(
            """{"to":"Ada","title":"do my own work"}""",
            peerNames = listOf("Bor", "Cem"),
            selfName = "Ada",
        )
        assertNotNull(call.problem)
        assertTrue("the refusal must name the real reason", call.problem!!.contains("yourself"))
    }

    @Test
    fun `an unknown recipient is refused with the roster`() {
        val call = AssignTaskTool.parse(
            """{"to":"Deniz","title":"x"}""",
            peerNames = listOf("Ada", "Bor"),
            selfName = "Ada",
        )
        assertNotNull(call.problem)
        assertTrue(call.problem!!.contains("Ada"))
    }

    @Test
    fun `assignment quota and the recipient's open list are separate limits`() {
        assertNull(AssignTaskTool.quotaRefusal(0, 0))
        val round = AssignTaskTool.quotaRefusal(AssignTaskTool.PER_ROUND, 0)
        assertNotNull(round)
        assertTrue(round!!.contains("position"))

        val busy = AssignTaskTool.quotaRefusal(0, AssignTaskTool.MAX_OPEN)
        assertNotNull(busy)
        assertTrue(busy!!.contains("${AssignTaskTool.MAX_OPEN}"))
        assertFalse(round == busy)
    }

    @Test
    fun `the assignment result says plainly that nothing ran`() {
        val text = AssignTaskTool.result("t_ab12", "Ada", "check the API")
        assertTrue(text.contains("t_ab12"))
        assertTrue(text.contains("Nothing runs now"))
    }

    @Test
    fun `a valid call keeps the canonical peer name`() {
        val call = AssignTaskTool.parse(
            """{"to":"ada","title":"check","expect":"a URL","why":"C3"}""",
            peerNames = listOf("Ada", "Bor"),
            selfName = "Bor",
        )
        assertNull(call.problem)
        assertEquals("Ada", call.to)
        assertEquals("a URL", call.expect)
        assertEquals("C3", call.why)
    }
}
