package com.openminis.app.agent.team

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * [MINIS-OZEL 11.5 · 37050] `task_list` and `task_update`: the board, from inside
 * a run.
 *
 * ## Why two tools and not one with an action
 *
 * They are used at different moments and by different roles. Reading is what every
 * member does at the start of its turn ("what am I supposed to do"); writing is a
 * deliberate act with consequences. Splitting them means the read can be exposed
 * to a member that may not write a board at all (an observer), and it keeps the
 * write's description short enough that a model actually follows the evidence
 * rule instead of skimming past it.
 *
 * ## What is deliberately NOT a tool
 *
 * There is no `task_delete`. A task that was wrong is DROPPED with a reason, so
 * the board keeps the record of the decision. Deletion stays a user action on the
 * screen — the one place where "I made a mistake" is unambiguous.
 */
object TaskTools {

    const val LIST_NAME = "task_list"
    const val UPDATE_NAME = "task_update"

    /**
     * The board a member reads.
     *
     * `owner` is filled from the member's own name by the caller, never from the
     * model — a member that could name any owner would read the whole board on
     * every turn and lose the point of having its own tasks.
     */
    fun listDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = LIST_NAME,
        description = "List the tasks on this council's board. With no arguments it returns YOUR " +
            "own open tasks; pass `all` to see every task on the board (owner, state, evidence " +
            "count). Use it at the start of a round to see what you were given, and before " +
            "adding a task so you do not duplicate one.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary, e.g. 'Check my open tasks'. Use the same language " +
                    "as the user.",
            ),
            "all" to AgentToolParam(
                "boolean",
                "Optional. true returns the whole board instead of only your own open tasks.",
            ),
            "state" to AgentToolParam(
                "string",
                "Optional. Filter to one state.",
                enumValues = listOf("open", "doing", "done", "dropped"),
            ),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "all", "state"),
    )

    /**
     * The board a member writes.
     *
     * One action per call, and the evidence rule is stated twice — in the tool
     * description and in the refusal — because a model that has nothing to show
     * will otherwise try `done` first, get a refusal, and try `done` again.
     */
    fun updateDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = UPDATE_NAME,
        description = "Change one task on this council's board. Actions: " +
            "`add` (open a new task: pass `title`, optional `detail`, `owner`), " +
            "`doing` (you are starting it), " +
            "`done` (you finished it — REQUIRES `evidence`, see below), " +
            "`drop` (it cannot be done — REQUIRES `reason`), " +
            "`assign` (give it to a member: pass `owner`), " +
            "`evidence` (attach proof to a task you already did). " +
            "EVIDENCE IS MANDATORY FOR `done`: one of a file path that exists, a URL, or the " +
            "output of the command/test you ran (pass it in `evidence` with `evidence_kind`). " +
            "\"I did it\" is not evidence — the board is what the user reads after the run, so a " +
            "done task with nothing attached is a claim, not a result.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary, e.g. 'Mark the API check done'. Use the same " +
                    "language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "What to do.",
                enumValues = listOf("add", "doing", "done", "drop", "assign", "evidence"),
            ),
            "id" to AgentToolParam(
                "string",
                "The task id (or its first 8 characters). Required for every action except `add`.",
            ),
            "title" to AgentToolParam("string", "For action=add: one line, what has to be done."),
            "detail" to AgentToolParam(
                "string",
                "For action=add: the constraint or acceptance test, if it is not obvious from the " +
                    "title.",
            ),
            "owner" to AgentToolParam(
                "string",
                "For action=add or assign: a member name, or \"user\". Empty leaves it unassigned.",
            ),
            "evidence" to AgentToolParam(
                "string",
                "For action=done or evidence: the proof itself — a path, a URL, or the pasted " +
                    "output.",
            ),
            "evidence_kind" to AgentToolParam(
                "string",
                "For action=done or evidence: what sort of proof `evidence` is. Defaults to " +
                    "`output`.",
                enumValues = listOf("path", "url", "output"),
            ),
            "reason" to AgentToolParam(
                "string",
                "For action=drop: why it cannot be done. Required — an unexplained drop is " +
                    "re-opened by the next run.",
            ),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf(
            "tool_title", "action", "id", "title", "detail", "owner",
            "evidence", "evidence_kind", "reason",
        ),
    )

    /**
     * Resolving a short id.
     *
     * A model that has seen `[t_ab12cd34]` in its task block will echo eight
     * characters, not the full id — and an exact-match-only resolver would refuse
     * every such call. A prefix match is safe here because ids are random and the
     * board is small; an ambiguous prefix is refused rather than guessed.
     */
    data class Resolved(val task: TeamTask?, val problem: String?)

    fun resolve(board: TaskBoard, rawId: String): Resolved {
        val id = rawId.trim()
        if (id.isEmpty()) return Resolved(null, "This action needs a task `id`.")
        board.find(id)?.let { return Resolved(it, null) }
        val matches = board.tasks.filter { it.id.startsWith(id, ignoreCase = true) }
        return when (matches.size) {
            0 -> Resolved(null, "No task with id \"$id\" on this board. Use task_list to see the ids.")
            1 -> Resolved(matches.first(), null)
            else -> Resolved(
                null,
                "\"$id\" matches ${matches.size} tasks (${matches.joinToString { it.id }}). " +
                    "Use more characters.",
            )
        }
    }

    /** The stored evidence row, built from the two argument fields. */
    fun evidenceOf(kindRaw: String?, value: String, by: String): TaskEvidence =
        TaskEvidence(
            kind = EvidenceKind.parse(kindRaw),
            value = value.trim().take(4000),
            by = by,
        )
}
