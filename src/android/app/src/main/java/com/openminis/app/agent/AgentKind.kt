package com.openminis.app.agent

/**
 * [MINIS-OZEL 11.5 · 37060] Which kind of agent is running — the third kind, and
 * the reason a boolean was not enough.
 *
 * ## The history, because it explains the shape
 *
 * Up to 11.4 the app had exactly two: the user's chat ([TOP]) and a sub agent it
 * delegated to ([HELPER]). One flag carried the difference
 * (`AgentTools.makeAgentTools(isHelper: Boolean)`) and two rules were derived
 * from it: a helper does not see `subagent_task` (depth stays 1) and does not
 * write long-term memory.
 *
 * A council member fits neither. It is not the user's conversation — it runs
 * inside a run, has no human in front of it, and must not start another team.
 * But it is not a sub agent either: it needs its own long-term memory, it must
 * be able to ask a colleague a question, it must be able to hand a task to a
 * colleague, and — the user's explicit requirement — it must be able to run its
 * OWN sub agents with their own browser and shell.
 *
 * With a boolean, "member" had to be spelled `isHelper = true`, which switched
 * off exactly the three things a member needs. The bug that follows is not
 * cosmetic: `isHelper = true` also makes `holdsSlot` false
 * (`ChatViewModel.kt:8714`), so a member stops taking a stream slot, while
 * `isHelper = false` gives it `team_run` and lets one council open another.
 * Neither branch is the member we want.
 *
 * ## The three belts, in one table
 *
 * | | TOP | MEMBER | HELPER |
 * |---|---|---|---|
 * | shell / browser / files | ✓ | ✓ | ✓ |
 * | memory_write | ✓ | ✓ own scope | ✗ |
 * | subagent_task | ✓ | ✓ counted against the run | ✗ |
 * | ask_teammate / assign_task | ✗ | ✓ | ✗ |
 * | team_run | ✓ | **✗** | ✗ |
 * | user_ask | ✓ blocks | ✓ does not block | ✗ |
 *
 * `MEMBER` losing `team_run` is not a depth rule — a member is already inside a
 * team. Letting it start one multiplies the budget by an unknown factor and
 * makes the report's owner ambiguous, which is exactly the property the user
 * asked for ("one answer, not five").
 */
enum class AgentKind {
    /** The user's own conversation. The only kind that may start a team. */
    TOP,

    /** A council member: a full agent inside a run, not the user's chat. */
    MEMBER,

    /** A sub agent delegated to by TOP or MEMBER. Depth 2, never 3. */
    HELPER,
    ;

    /**
     * The belt is built from the kind, but a caller that has not been updated
     * still passes `isHelper`. Resolving here keeps one place for the mapping:
     * an old call site can only ever produce TOP or HELPER, never MEMBER, so it
     * cannot accidentally grant the third kind.
     */
    companion object {
        fun of(isHelper: Boolean): AgentKind = if (isHelper) HELPER else TOP
    }
}
