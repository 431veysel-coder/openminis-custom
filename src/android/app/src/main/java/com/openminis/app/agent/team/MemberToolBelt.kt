package com.openminis.app.agent.team

import com.openminis.app.agent.AgentKind

/**
 * [MINIS-OZEL 11.5 · 37060] A member's tool belt, actually enforced.
 *
 * ## Why this class exists at all
 *
 * `TeamMember.tools` has been stored since 11.4 and read by nobody. The editor
 * even said so in a comment (`TeamEditorScreen.kt:43`: "a per-member checkbox
 * would be a promise the runner does not yet honour"). Storing a setting and
 * ignoring it is worse than not offering it: the user configures a `reviewer`
 * that must not write, the reviewer writes, and nothing anywhere says why.
 *
 * So the belt is derived in ONE place, from facts, and the result is a plain
 * set of tool names the schema builder intersects against. No tool is granted
 * by omission — an unknown name in [TeamMember.tools] is dropped, and a
 * deny-list entry always wins.
 *
 * ## Three inputs, in this precedence
 *
 *  1. **[AgentKind]** — a member never gets `team_run`; a helper never gets
 *     `subagent_task`. Not configurable.
 *  2. **Role preset** — `researcher` cannot shell, `reviewer` cannot write.
 *     The default when the user has not said otherwise.
 *  3. **[TeamMember.tools]** — an explicit allow-list. Non-empty means ONLY
 *     these; empty means "the role preset". This is the same convention the
 *     data class already documents ("empty = inherit"), kept so existing teams
 *     keep working.
 *
 * The scope (`AgentScope.Mode`) is NOT here: it is checked at the tool, against
 * the real path, and a per-member scope would be a second, weaker copy of that
 * check. What IS here is the extra path root a member may write to —
 * `/var/minis/spaces/sp_<member>/` — passed down as `extraRoots`.
 */
object MemberToolBelt {

    /** Tools a council member may ever hold. Anything else is refused by `AgentTools`. */
    val MEMBER_ALLOWED: Set<String> = setOf(
        "shell_execute",
        "browser_use",
        "file_read",
        "file_write",
        "file_edit",
        "read_image",
        "memory_write",
        "memory_get",
        "note",
        "journal",
        "verify",
        "subagent_task",
        "ask_teammate",
        "assign_task",
        "task_list",
        "task_update",
        "user_ask",
    )

    /**
     * Never a member's, whatever the role or the allow-list says.
     *
     * `team_run` is here for meaning, not depth (see [AgentKind]): a member is
     * already inside a team, so a nested team multiplies the budget and makes
     * the report's owner ambiguous.
     *
     * Deliberately NOT a long deny-list. The member ceiling is
     * [MEMBER_ALLOWED] — an allow-list — so anything not named there (the
     * `minis-*` CLIs, a future tool) is already unreachable, and a deny entry
     * per power tool would be a second list to keep in sync with the first.
     */
    val MEMBER_DENIED: Set<String> = setOf("team_run")

    /**
     * Role → default belt. Deliberately narrow: a role is a licence to do LESS.
     *
     * - `researcher` reads the world (browser, files, memory) and cannot touch a
     *   shell. A research member with `shell_execute` spends its round installing
     *   things instead of answering.
     * - `engineer` runs and edits.
     * - `reviewer` reads and reports, and may hand work back — it does not fix.
     * - `critic` reads and argues. No writing, no execution.
     * - empty/unknown role → the generalist belt, minus what [MEMBER_DENIED] holds.
     */
    private val ROLE_PRESETS: Map<String, Set<String>> = mapOf(
        "researcher" to setOf(
            "browser_use", "file_read", "read_image",
            "memory_write", "memory_get", "note", "journal",
            "ask_teammate", "assign_task", "task_list", "task_update", "verify",
        ),
        "engineer" to setOf(
            "shell_execute", "browser_use", "file_read", "file_write", "file_edit",
            "read_image", "memory_write", "memory_get", "note", "journal", "verify",
            "subagent_task", "ask_teammate", "assign_task", "task_list", "task_update",
        ),
        "reviewer" to setOf(
            "browser_use", "file_read", "read_image",
            "memory_write", "memory_get", "note", "journal", "verify",
            "ask_teammate", "assign_task", "task_list", "task_update",
        ),
        "critic" to setOf(
            "browser_use", "file_read", "read_image",
            "memory_get", "note", "verify",
            "ask_teammate", "assign_task", "task_list",
        ),
    )

    /**
     * The belt for one member.
     *
     * @param kind the running agent kind. For anything but [AgentKind.MEMBER] the
     *   result is empty and the caller keeps its own belt — this object is only
     *   authoritative for members.
     * @param role the member's `role` sentence or keyword. Matched case-insensitively
     *   on the whole string first, then on the first word, so "researcher" and
     *   "researcher: pricing" both resolve.
     * @param declared `TeamMember.tools`. Empty = use the role preset.
     */
    fun resolve(kind: AgentKind, role: String, declared: List<String>): Set<String> {
        if (kind != AgentKind.MEMBER) return emptySet()
        val wanted = if (declared.isNotEmpty()) {
            declared.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } else {
            rolePreset(role) ?: (MEMBER_ALLOWED - MEMBER_DENIED)
        }
        // Deny wins, then the global member ceiling. Both filters run in that
        // order so a role preset that forgot a name cannot leak it.
        return (wanted intersect MEMBER_ALLOWED) - MEMBER_DENIED
    }

    fun rolePreset(role: String): Set<String>? {
        val text = role.trim().lowercase()
        if (text.isEmpty()) return null
        ROLE_PRESETS[text]?.let { return it }
        val first = text.split(' ', ',', ':', '—', '-').firstOrNull { it.isNotBlank() }
        if (first != null) ROLE_PRESETS[first]?.let { return it }
        // Turkish and English spellings of the four roles, because a user writing
        // the team in Turkish should not silently get the generalist belt.
        val alias = when (first) {
            "araştırmacı", "arastirmaci", "research" -> "researcher"
            "yazılımcı", "yazilimci", "geliştirici", "gelistirici", "developer", "coder" -> "engineer"
            "inceleyici", "gözden geçiren", "gozden geciren", "review" -> "reviewer"
            "eleştirmen", "elestirmen", "şüpheci", "supheci" -> "critic"
            else -> null
        }
        return alias?.let { ROLE_PRESETS[it] }
    }

    /** Roles the editor offers as one-tap chips. Order = display order. */
    val KNOWN_ROLES: List<String> = listOf("researcher", "engineer", "reviewer", "critic")

    /**
     * A one-line answer to "why can this member not do X", for the refusal text.
     *
     * A refused tool that says only "not allowed" teaches nothing and produces a
     * retry; naming the role and the setting produces a correction. This is the
     * same rule `AgentScope.writeRefusal` follows.
     */
    fun refusal(tool: String, role: String, declared: List<String>): String {
        val why = when {
            tool in MEMBER_DENIED -> "it is reserved for the user's own conversation"
            declared.isNotEmpty() -> "the team defines an explicit tool list for this member"
            rolePreset(role) != null -> "its role \"${role.trim()}\" does not include it"
            else -> "this member's belt does not include it"
        }
        return "Error: a council member cannot use $tool — $why. " +
            "Use a tool you have, or hand the step to a colleague with assign_task."
    }
}