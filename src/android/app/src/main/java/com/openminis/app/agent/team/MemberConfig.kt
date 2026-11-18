package com.openminis.app.agent.team

import com.openminis.app.agent.AgentKind

/**
 * [MINIS-OZEL 11.5 · 37060] What marks a session as a council member.
 *
 * ## Why this is not [com.openminis.app.agent.jobs.HelperConfig]
 *
 * `helperConfig` is a three-in-one decision the app already relies on:
 *
 *  1. `isHelper` → the child belt (no `subagent_task`, no memory, no `user_ask`);
 *  2. `holdsSlot = helperConfig == null` (`ChatViewModel.kt:8714`) → the child
 *     does not take one of the six global stream slots;
 *  3. `fsSessionId = helperConfig?.parentSessionId` → the child shares the
 *     parent's `/var/minis` bind mounts.
 *
 * A member needs the OPPOSITE of all three: it wants `subagent_task`, it wants
 * its own memory, and it must take a stream slot (the whole point of the wave
 * policy is that a member occupies one of the six). Reusing `helperConfig` and
 * flipping flags would mean editing three call sites that also serve real sub
 * agents, where the current behaviour is correct and load-bearing.
 *
 * So a member gets its own marker. `helperConfig == null` stays true for it,
 * which is exactly what makes it take a slot — no change to the concurrency
 * manager at all.
 *
 * ## What it carries
 *
 * [runId] and [teamId] scope the member's memory and evidence; [role] and
 * [tools] feed [MemberToolBelt]; [peers] is what `ask_teammate`'s enum will use
 * (37038) and what the member prompt already lists; [sessionKey] is the
 * `sp_<member>` workspace identity (37061).
 */
data class MemberConfig(
    val runId: String,
    val teamId: String,
    val memberName: String,
    val role: String = "",
    /** Empty = the role preset. See [MemberToolBelt.resolve]. */
    val tools: List<String> = emptyList(),
    /** The other members' names, for the prompt and the peer tool. */
    val peers: List<String> = emptyList(),
    /**
     * The workspace / terminal / browser identity for this member. Null until
     * 37061 stamps it; when null the member shares the parent's id, which is
     * the pre-isolation behaviour and must not be mistaken for isolation.
     */
    val sessionKey: String? = null,
) {
    val kind: AgentKind get() = AgentKind.MEMBER

    /** True when the member has its own workspace identity. */
    val isolated: Boolean get() = !sessionKey.isNullOrBlank()

    /**
     * [37063] The shell's takeover state, shared between the member screen and
     * the tool dispatcher.
     *
     * On the config rather than on the VM so both sides read the SAME object: the
     * screen grants the takeover and the dispatcher must refuse `shell_execute`
     * in the same instant. A copy on either side would let the user type into a
     * shell the agent believes it owns.
     *
     * NOT part of the identity of the config, so it is excluded from `equals`:
     * re-stamping the marker on every round builds an equal config, and a user's
     * active takeover must survive that. The runtime carries the object forward
     * explicitly (`stampMemberConfig`).
     */
    val terminal: TerminalTakeover = TerminalTakeover()
}
