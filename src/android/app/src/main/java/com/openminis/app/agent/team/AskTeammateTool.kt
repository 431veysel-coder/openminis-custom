package com.openminis.app.agent.team

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * [MINIS-OZEL 11.5 · 37065] `ask_teammate` — a member asking a colleague one
 * question and getting one answer back.
 *
 * ## Why this is a real tool and not a line in the prompt
 *
 * 11.4's member prompt already told the model "you may ask a colleague with
 * `ask_teammate(name, question)`". There was no such tool. A model that followed
 * the instruction got `Unknown tool`, which is the worst of both worlds: the
 * prompt promises a capability, the dispatcher denies it, and the model spends a
 * turn discovering that its own brief lied.
 *
 * ## Why not just the queue
 *
 * The position JSON already carries `questions[]`, and that IS the default path
 * (free, parallel, no re-entry). The tool exists for the one case the queue
 * cannot serve: a member that cannot proceed until it knows something — "which
 * SDK version is this repo on" — because a queued question is only answered in
 * the NEXT round, and by then the member has already guessed.
 *
 * ## The limits, and why each one is there
 *
 * | Limit | Value | Reason |
 * |---|---|---|
 * | Depth | 1 | the answering member cannot ask back: two agents answering each other's questions is a loop with no floor |
 * | Answer shape | no tools, no history, ≤ 5 sentences | it must be cheap; a peer with a shell is a second council |
 * | Quota | per member per round, per run | a member that asks eight questions has not thought first |
 * | Same question | deduplicated | the retry path is the same question rephrased |
 * | Timeout | short, and the member decides after | a peer that hangs must not hold the round |
 */
object AskTeammateTool {

    const val NAME = "ask_teammate"

    /** Questions one member may ask per ROUND. */
    const val PER_ROUND = 3

    /** Questions one member may ask for the whole run. */
    const val PER_RUN = 8

    /** How long a peer answer may take before the asker is told to decide alone. */
    const val TIMEOUT_MS = 60_000L

    fun definition(peerNames: List<String>): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Ask ONE colleague on this team a single, closed question and get a short " +
            "answer back. Use it when their expertise decides your next step and you cannot check " +
            "it yourself — not to hand work over, and not to chat. " +
            "The answer is at most five sentences and comes WITHOUT tools, so ask about something " +
            "they would already know from their own role (a format, a convention, whether a claim " +
            "looks right). If you only need the answer by the NEXT round, put the question in your " +
            "position JSON instead — that is free and does not block you.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary, e.g. 'Ask Ada about the licence'. Use the same " +
                    "language as the user.",
            ),
            "name" to AgentToolParam(
                "string",
                "Which colleague to ask. One of the members of this team.",
                enumValues = peerNames,
            ),
            "question" to AgentToolParam(
                "string",
                "One question, closed and specific. 'Is the API paginated?' beats 'tell me about " +
                    "the API'. The answer carries no tools, so do not ask for anything that needs " +
                    "a fresh lookup.",
            ),
        ),
        required = listOf("tool_title", "name", "question"),
        propertyOrdering = listOf("tool_title", "name", "question"),
    )

    /**
     * Parsed call. Null [problem] means it may run.
     *
     * Validation lives here rather than in the dispatcher so the refusals are the
     * same text the tests assert, and so a member cannot learn a second, subtly
     * different rule from a different call site.
     */
    data class Call(val name: String, val question: String, val problem: String? = null)

    fun parse(argsJson: String, peerNames: List<String>): Call {
        val obj = runCatching { org.json.JSONObject(argsJson) }.getOrNull()
            ?: return Call("", "", "ask_teammate needs a JSON object with `name` and `question`.")
        val name = obj.optString("name").trim()
        val question = obj.optString("question").trim()
        val problem = when {
            name.isEmpty() || question.isEmpty() ->
                "ask_teammate needs both `name` and `question`."
            peerNames.none { it.equals(name, ignoreCase = true) } ->
                "There is no teammate called \"$name\". The members of this team are: " +
                    peerNames.joinToString(", ") + "."
            else -> null
        }
        return Call(
            name = peerNames.firstOrNull { it.equals(name, ignoreCase = true) } ?: name,
            question = question,
            problem = problem,
        )
    }

    /**
     * The quota check, as one call: which limit, if any, this question hits.
     *
     * Both counters are passed in rather than held here because the caller already
     * owns them per member, and a static counter would leak between runs.
     */
    fun quotaRefusal(askedThisRound: Int, askedThisRun: Int): String? {
        if (askedThisRound >= PER_ROUND) {
            return "You have already asked $askedThisRound questions this round (limit $PER_ROUND). " +
                "Ask one more and the round stops being a discussion and becomes an interview. " +
                "Decide with what you have, and put anything still open in your position's " +
                "`questions` for the next round."
        }
        if (askedThisRun >= PER_RUN) {
            return "You have asked $askedThisRun questions in this run (limit $PER_RUN). " +
                "From here on, state your uncertainty in the position itself — the synthesiser " +
                "keeps it and the user reads it as a gap rather than losing it in a chat."
        }
        return null
    }

    /** The question as the peer receives it. */
    fun peerPrompt(asker: String, question: String, role: String?): String = buildString {
        appendLine("$asker, another member of this team, asks you one question.")
        if (!role.isNullOrBlank()) appendLine("They describe you as: ${role.trim()}")
        appendLine()
        appendLine("QUESTION:")
        appendLine(question.trim().take(1000))
        appendLine()
        appendLine("Answer in at most 5 sentences. You have no tools this turn: if the answer needs")
        appendLine("a fresh lookup, say so plainly instead of guessing.")
    }

    /** What the asker sees when the peer produced nothing usable. */
    fun noAnswer(name: String, why: String): String =
        "$name did not answer ($why). Decide with what you have, and say in your position that " +
            "this is what you could not confirm."

    /** Result text handed back to the asking model. */
    fun result(name: String, answer: String): String =
        "Answer from $name:\n${answer.trim().take(1200)}"
}
