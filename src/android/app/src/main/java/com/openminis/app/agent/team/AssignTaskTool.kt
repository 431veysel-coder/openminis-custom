package com.openminis.app.agent.team

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * [MINIS-OZEL 11.5 · 37051] `assign_task` — giving a colleague work, live.
 *
 * ## How this differs from `ask_teammate`, and why both exist
 *
 * A question wants an ANSWER: one turn, one reply, the asker waits. A task wants
 * WORK: asynchronous, may take longer than the run, needs evidence before it can
 * be called done. Collapsing the two into one tool would force every hand-over to
 * be either blocking (a question) or answerless (a task), and members genuinely
 * need both.
 *
 * ## The design rule that keeps this from being a trap
 *
 * **This tool does not run anything.** It writes a row on the board and returns.
 * The temptation is to start the work immediately — and that would put an
 * unbudgeted agent loop inside a member's turn, with the round's wave size no
 * longer meaning anything. The board is the queue; the next round (or the next
 * run, or the user) picks it up.
 *
 * ## The limits
 *
 * | Limit | Value | Reason |
 * |---|---|---|
 * | Self-assignment | refused | a member inventing work for itself and then reporting it done is the shortest path to a fake result |
 * | Depth | 1 | a task creates no agent, so depth is structural; the limit is the quota below |
 * | Per round | 2 | more is a plan, and a plan belongs in the position's `tasks[]` where it costs nothing |
 * | Per run | 15 open | an open list past this stops being read, by members or by the user |
 */
object AssignTaskTool {

    const val NAME = "assign_task"

    const val PER_ROUND = 2
    const val MAX_OPEN = 15

    fun definition(peerNames: List<String>): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Hand a piece of WORK to a colleague: it goes on the council's task board and " +
            "they pick it up in a later round (or the next run). Use it when the job needs their " +
            "tools or their expertise and you cannot do it yourself. " +
            "This does NOT run anything now — it writes the task down and returns. " +
            "For a question you need answered immediately, use ask_teammate instead; for work you " +
            "only want noted, put it in your position's `tasks` (free, no turn spent).",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary, e.g. 'Hand the pricing check to Ada'. Use the same " +
                    "language as the user.",
            ),
            "to" to AgentToolParam(
                "string",
                "Which colleague does the work. One of this team's members.",
                enumValues = peerNames,
            ),
            "title" to AgentToolParam(
                "string",
                "One line: what has to be done. Concrete enough that they can start without " +
                    "asking you what you meant.",
            ),
            "expect" to AgentToolParam(
                "string",
                "Optional but strongly wanted: what finished looks like — the output, the file, " +
                    "the number. This is what they will attach as evidence.",
            ),
            "why" to AgentToolParam(
                "string",
                "Optional: which claim or decision this serves (e.g. 'C3'). Lets the board show " +
                    "why the work exists.",
            ),
        ),
        required = listOf("tool_title", "to", "title"),
        propertyOrdering = listOf("tool_title", "to", "title", "expect", "why"),
    )

    data class Call(
        val to: String,
        val title: String,
        val expect: String = "",
        val why: String = "",
        val problem: String? = null,
    )

    /**
     * Parsing plus every refusal that can be decided without the board.
     *
     * The self-assignment check is here rather than in the caller because it is a
     * rule about the tool's meaning, not about a particular board — and the refusal
     * text has to name the real reason, or the member retries with its own name
     * spelled differently.
     */
    fun parse(
        argsJson: String,
        peerNames: List<String>,
        selfName: String,
    ): Call {
        val obj = runCatching { org.json.JSONObject(argsJson) }.getOrNull()
            ?: return Call("", "", problem = "assign_task needs `to` and `title`.")
        val to = obj.optString("to").trim()
        val title = obj.optString("title").trim()
        val expect = obj.optString("expect").trim().take(400)
        val why = obj.optString("why").trim().take(120)

        val problem = when {
            to.isEmpty() || title.isEmpty() ->
                "assign_task needs both `to` and `title`."
            to.equals(selfName, ignoreCase = true) ->
                "You cannot assign work to yourself: put it in your own position instead. A task " +
                    "you gave yourself and then marked done is a claim with no reader."
            peerNames.none { it.equals(to, ignoreCase = true) } ->
                "There is no teammate called \"$to\". The members are: " +
                    peerNames.joinToString(", ") + "."
            else -> null
        }
        return Call(
            to = peerNames.firstOrNull { it.equals(to, ignoreCase = true) } ?: to,
            title = title,
            expect = expect,
            why = why,
            problem = problem,
        )
    }

    /** Quota check. [openForTarget] is the recipient's current open-task count. */
    fun quotaRefusal(assignedThisRound: Int, openForTarget: Int): String? = when {
        assignedThisRound >= PER_ROUND ->
            "You have already handed out $assignedThisRound tasks this round (limit $PER_ROUND). " +
                "Past that it is a plan, not a hand-over — put the rest in your position's `tasks`."
        openForTarget >= MAX_OPEN ->
            "That colleague already has $openForTarget open tasks (limit $MAX_OPEN), so this one " +
                "was not added. Do it yourself, or put it in your position so the chair can weigh it."
        else -> null
    }

    /** What the assigner sees. Says plainly that nothing ran. */
    fun result(taskId: String, to: String, title: String): String =
        "Task [$taskId] written for $to: \"${title.take(120)}\". " +
            "Nothing runs now — they will pick it up in a later round, and it stays on the board " +
            "after this run ends. Do not assign it again."
}
