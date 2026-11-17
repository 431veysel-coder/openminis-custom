package com.openminis.app.agent.team

import com.openminis.app.data.team.TeamMember

/**
 * [MINIS-OZEL 11.4 S2] The prompts. Kept in one file, as pure string builders,
 * because a prompt is the only part of an agent team that cannot be tested by
 * running it: if it is not reviewable in one screen it will drift.
 *
 * Three things are deliberate:
 *
 *  1. **Rol sabitleme (CAMEL inception prompting).** Each member is told what it
 *     is and — more importantly — what it is NOT: a member answers the
 *     instruction it was given and does not re-plan the whole task. Without that
 *     sentence every member behaves like a second orchestrator; the reference
 *     failure is "disobey role specification" in MAST.
 *  2. **Akran listesi ilk turda.** A member that does not know who else is on
 *     the team cannot ask anyone, and asking is the point of a team. The list is
 *     injected as data, once, not re-derived from a shared transcript.
 *  3. **Sonlanma koşulları açık.** "When to stop" is stated in the prompt AND
 *     enforced by [TeamBudget]: the prompt exists to make the model cooperate,
 *     the budget exists because it might not.
 */
object TeamPrompts {

    /**
     * The orchestrator's first job: fill the task ledger before anybody works.
     *
     * Educated guesses are separated from facts on purpose. A model that mixes
     * them produces a plan built on things nobody checked; labelled guesses can
     * be used late, when being wrong is cheaper than being empty.
     */
    fun taskLedger(teamName: String, members: List<TeamMember>, task: String): String = buildString {
        appendLine("You are the orchestrator of the team \"$teamName\".")
        appendLine("Your team:")
        members.forEach { appendLine("- ${it.name}: ${it.role.ifBlank { "team member" }}") }
        appendLine()
        appendLine("TASK:")
        appendLine(task)
        appendLine()
        appendLine("Before anyone starts, write a task ledger as a single JSON object:")
        appendLine("""{"facts":[],"to_look_up":[],"to_derive":[],"guesses":[],"plan":[{"step":"","assignee":""}]}""")
        appendLine("Rules:")
        appendLine("- facts: only what the task text already states or is certain.")
        appendLine("- to_look_up: what a tool must find (web, files, shell).")
        appendLine("- to_derive: what must be reasoned out or computed.")
        appendLine("- guesses: plausible assumptions, clearly marked as guesses. Never put a guess in facts.")
        appendLine("- plan: at most ${TaskLedger.maxSteps(members.size)} steps, each assigned to one member by exact name.")
        appendLine("Answer with the JSON object only.")
    }

    /**
     * The inner loop's question sheet. The five keys are fixed because the
     * parser keys off them; anything else the model wants to say belongs in a
     * reason string.
     */
    fun progressLedger(
        task: String,
        ledger: TaskLedger,
        members: List<TeamMember>,
        lastInstruction: String?,
        lastResult: String?,
    ): String = buildString {
        appendLine("TASK: $task")
        appendLine()
        appendLine("PLAN:")
        ledger.plan.forEachIndexed { i, step -> appendLine("${i + 1}. [${step.assignee}] ${step.step}") }
        if (ledger.facts.isNotEmpty()) {
            appendLine()
            appendLine("KNOWN FACTS:")
            ledger.facts.forEach { appendLine("- $it") }
        }
        if (ledger.toLookUp.isNotEmpty() || ledger.toDerive.isNotEmpty()) {
            appendLine()
            appendLine("STILL OPEN:")
            (ledger.toLookUp + ledger.toDerive).forEach { appendLine("- $it") }
        }
        if (lastInstruction != null) {
            appendLine()
            appendLine("JUST ASKED:")
            appendLine(lastInstruction)
            appendLine("ITS RESULT:")
            appendLine(lastResult?.take(MAX_RESULT_CHARS) ?: "(no result)")
        }
        appendLine()
        appendLine("Team members (use these exact names):")
        members.forEach { appendLine("- ${it.name}") }
        appendLine()
        appendLine("Answer with ONE JSON object, exactly these keys:")
        appendLine(
            """{"is_request_satisfied":{"answer":false,"reason":""},""" +
                """"is_progress_being_made":{"answer":true,"reason":""},""" +
                """"is_in_loop":{"answer":false,"reason":""},""" +
                """"instruction_or_question":{"answer":""},""" +
                """"next_speaker":{"answer":""}}""",
        )
        appendLine("Rules:")
        appendLine("- is_request_satisfied: true ONLY when the deliverable exists and is verified, not when it looks close.")
        appendLine("- is_progress_being_made: false if this turn added nothing new.")
        appendLine("- is_in_loop: true if the team is repeating a step it already did.")
        appendLine("- instruction_or_question: the concrete instruction for the next speaker. One step, not a plan.")
        appendLine("- next_speaker: one member name from the list. Empty only when satisfied is true.")
        appendLine("Answer with the JSON object only, no prose around it.")
    }

    /**
     * A member's standing brief. [peers] comes from the roster, so a member can
     * ask a colleague without the orchestrator relaying the question.
     */
    fun member(
        member: TeamMember,
        peers: List<TeamMember>,
        scopeNote: String?,
    ): String = buildString {
        appendLine("You are \"${member.name}\", a member of a team working on one shared task.")
        if (member.role.isNotBlank()) {
            appendLine("Your role: ${member.role}")
        }
        if (peers.isNotEmpty()) {
            appendLine()
            appendLine("Your colleagues (not your subordinates, not your users):")
            peers.filter { it.name != member.name }.forEach { peer ->
                appendLine("- ${peer.name}: ${peer.role.ifBlank { "team member" }}")
            }
            appendLine()
            appendLine("You may ask a colleague a single question with ask_teammate(name, question).")
            appendLine("Use it when their expertise decides the answer. Do not use it to hand your work over,")
            appendLine("and do not ask more than once for the same thing — you get one answer, not a dialogue.")
        }
        appendLine()
        appendLine("What is expected of you:")
        appendLine("- Do the step you were given, with your tools, and report what you actually did.")
        appendLine("- You are NOT the planner. Do not re-plan the whole task or reassign work.")
        appendLine("- Use your tools (shell, browser, files) instead of guessing when the answer is checkable.")
        appendLine("- If the instruction cannot be completed, say what blocked it and what you tried. Never report success you did not verify.")
        appendLine("- Reply with a short report: what you did, the result, and any artifact (path, URL, value).")
        if (!scopeNote.isNullOrBlank()) {
            appendLine()
            appendLine("Limits on this run: $scopeNote")
        }
    }

    /**
     * The check that stands between "the model says it is done" and "it is done".
     *
     * MAST's most frequent single failure is incorrect verification, and the
     * cheapest defence is a second, differently-worded look at the deliverable by
     * the orchestrator with the task text in front of it. It is not a proof; it
     * is the difference between an unchecked claim and a checked one.
     */
    fun verification(task: String, deliverable: String): String = buildString {
        appendLine("TASK was:")
        appendLine(task)
        appendLine()
        appendLine("The team reports it is finished. What it produced:")
        appendLine(deliverable.take(MAX_DELIVERABLE_CHARS))
        appendLine()
        appendLine("Check the deliverable against the task. Answer with ONE JSON object:")
        appendLine("""{"verified":true,"missing":[""],"reason":""}""")
        appendLine("Rules:")
        appendLine("- verified: true only if the deliverable answers what was asked, concretely.")
        appendLine("- missing: the concrete gaps. Empty when verified.")
        appendLine("- A claim without evidence (\"I fixed it\") is NOT verified.")
    }

    /** Fed back into the inner loop when verification fails, so the fix is targeted. */
    fun verificationFailure(missing: List<String>): String =
        "Verification failed. Still missing:\n" + missing.joinToString("\n") { "- $it" }

    const val MAX_RESULT_CHARS = 4000
    const val MAX_DELIVERABLE_CHARS = 8000
}
