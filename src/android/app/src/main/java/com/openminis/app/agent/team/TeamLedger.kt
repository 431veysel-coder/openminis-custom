package com.openminis.app.agent.team

import org.json.JSONObject

/**
 * [MINIS-OZEL 11.4 S2] The two ledgers an orchestrator keeps, and the rules that
 * turn them into a decision. Pure: no model call, no I/O, no Compose — so the
 * loop's control flow is testable without a network, which is the only way it
 * can be trusted before it is allowed to spend the user's tokens.
 *
 * ## Where this comes from
 *
 * Magentic-One (arXiv 2411.04468) keeps two documents: a TASK LEDGER (what is
 * known, what must be looked up, what must be derived, educated guesses, and a
 * plan) that the OUTER loop rewrites, and a PROGRESS LEDGER (five questions,
 * answered every turn as JSON) that the INNER loop runs on. The split matters:
 * a single "what next?" prompt has no memory of the plan, so an agent that gets
 * stuck re-derives a different plan every turn and oscillates. Rewriting the
 * task ledger only when the inner loop STALLS keeps the plan stable long enough
 * to be executed.
 *
 * Two of their details are load-bearing and are reproduced here rather than
 * simplified:
 *
 *  - The stall counter decrements on a productive turn. Three failures in a row
 *    mean "re-plan"; three failures spread across ten good turns are normal.
 *  - `is_progress_being_made` and `is_in_loop` are checked with `else if`, not
 *    as two separate increments: a turn that is both stuck and looping is one
 *    stall, not two. Counting it twice re-plans after two turns instead of
 *    three, and a re-plan is the expensive operation.
 */
data class LedgerAnswer(val answer: Boolean, val reason: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("answer", answer)
        put("reason", reason)
    }
}

/** The outer loop's short-term memory for one run. */
data class TaskLedger(
    /** Given or verified. */
    val facts: List<String> = emptyList(),
    /** Must be looked up (web, files, tools). */
    val toLookUp: List<String> = emptyList(),
    /** Must be derived (reasoned out, computed). */
    val toDerive: List<String> = emptyList(),
    /** Educated guesses — usable only when stuck, and always labelled as guesses. */
    val guesses: List<String> = emptyList(),
    val plan: List<PlanStep> = emptyList(),
) {
    data class PlanStep(val step: String, val assignee: String)

    fun isUsable(participants: List<String>): Boolean = plan.isNotEmpty()

    companion object {
        /**
         * A plan holds at most one step per member plus one: longer plans are
         * re-read every turn by every request, and a run that needs twelve
         * pre-written steps has a task that should have been split.
         */
        fun maxSteps(memberCount: Int): Int = memberCount + 1
    }
}

/** The inner loop's answer sheet for a single turn. */
data class ProgressLedger(
    val satisfied: LedgerAnswer,
    val progress: LedgerAnswer,
    val inLoop: LedgerAnswer,
    /** What the next speaker is told to do. Empty when satisfied. */
    val instruction: String,
    /** Must be one of the participant names; empty when satisfied. */
    val nextSpeaker: String,
)

/**
 * Parses the progress ledger out of a model reply.
 *
 * Written defensively on purpose. The ledger is the one place where a cheap
 * model's sloppiness becomes a crash: the reply arrives as prose with a JSON
 * object somewhere inside it, sometimes fenced, sometimes with a trailing
 * comment. Magentic-One retries the whole request on a bad ledger and gives up
 * after `max_json_retries`; we do the same at the loop level, so the parser's
 * job is to say clearly "this reply is unusable" instead of guessing.
 */
object ProgressLedgerParser {

    /** Extracts the first balanced JSON object from free text. Null if none. */
    fun extractJsonObject(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * @return the ledger, or null when the reply cannot be used. Callers must
     *   treat null as "ask again", never as "the task is done".
     */
    fun parse(raw: String?, participants: List<String>): ProgressLedger? {
        val json = extractJsonObject(raw) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null

        val satisfied = answer(obj, "is_request_satisfied") ?: return null
        val progress = answer(obj, "is_progress_being_made") ?: return null
        val inLoop = answer(obj, "is_in_loop") ?: return null
        val instruction = instructionOf(obj) ?: return null
        val speaker = nextSpeakerOf(obj) ?: return null

        // A satisfied run needs no speaker; an unsatisfied one must name a real
        // member. Accepting an unknown name would silently drop the turn.
        if (!satisfied.answer) {
            if (participants.isEmpty()) return null
            if (participants.none { it.equals(speaker, ignoreCase = true) }) {
                // Tolerate "Member (Role)" and backticked names — models do this
                // constantly, and it is unambiguous which member is meant.
                val matched = participants.firstOrNull { p ->
                    speaker.contains(p, ignoreCase = true)
                } ?: return null
                return ProgressLedger(satisfied, progress, inLoop, instruction, matched)
            }
            val exact = participants.first { it.equals(speaker, ignoreCase = true) }
            return ProgressLedger(satisfied, progress, inLoop, instruction, exact)
        }
        return ProgressLedger(satisfied, progress, inLoop, instruction, "")
    }

    private fun answer(obj: JSONObject, key: String): LedgerAnswer? {
        val node = obj.optJSONObject(key) ?: return null
        if (!node.has("answer")) return null
        return LedgerAnswer(
            answer = node.optBoolean("answer", false),
            reason = node.optString("reason").orEmpty(),
        )
    }

    private fun instructionOf(obj: JSONObject): String? {
        val node = obj.optJSONObject("instruction_or_question") ?: return null
        // The answer may be a string, or an object whose "answer" is the text —
        // both shapes appear in the reference implementation's own retries.
        val text = when {
            node.opt("answer") is String -> node.optString("answer")
            node.opt("question") is String -> node.optString("question")
            else -> ""
        }
        if (text.isBlank()) return null
        return text.trim()
    }

    private fun nextSpeakerOf(obj: JSONObject): String? {
        val node = obj.optJSONObject("next_speaker") ?: return null
        val text = node.optString("answer").orEmpty()
        return if (text.isBlank()) null else text.trim()
    }
}

/**
 * The stall rule, isolated so it can be tested against the sequence that
 * actually broke things: productive, productive, stuck, stuck, stuck.
 */
class StallTracker(private val maxStalls: Int = DEFAULT_MAX_STALLS) {

    var stalls: Int = 0
        private set
    var replans: Int = 0
        private set

    /**
     * The stall count at the moment of the LAST decision — the number that
     * triggered a re-plan, kept after the counter resets. A caller reporting
     * "how stuck did this run get" needs this, not [stalls].
     */
    var lastDecisionCount: Int = 0
        private set

    /** @return true when the caller must leave the inner loop and re-plan. */
    fun after(progress: ProgressLedger): Boolean {
        if (!progress.progress.answer) {
            stalls += 1
        } else if (progress.inLoop.answer) {
            stalls += 1
        } else {
            stalls = maxOf(0, stalls - 1)
        }
        lastDecisionCount = stalls
        val replan = stalls >= maxStalls
        if (replan) {
            replans += 1
            stalls = 0
        }
        return replan
    }

    companion object {
        /**
         * Three, matching the reference. Two re-plans a run that merely had a
         * bad turn; four lets a genuinely looping agent burn a whole budget.
         */
        const val DEFAULT_MAX_STALLS = 3
    }
}

/**
 * Termination, written down once so no prompt has to re-state it and no caller
 * can forget a case. MAST counts "agents unaware of termination conditions"
 * among the most common design failures; the fix is that the condition lives in
 * code, not in a sentence the model may or may not read.
 */
data class TeamBudget(
    val maxRounds: Int = 12,
    val maxStalls: Int = StallTracker.DEFAULT_MAX_STALLS,
    /** Wall clock for the whole run, in minutes. */
    val maxMinutes: Int = 20,
    /** Ledger retries per turn before the turn is abandoned. */
    val maxLedgerRetries: Int = 3,
) {
    companion object {
        const val MIN_ROUNDS = 2
        const val MAX_ROUNDS = 40
        const val MAX_MINUTES_CAP = 60
    }
}
