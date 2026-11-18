package com.openminis.app.agent.team

/**
 * [MINIS-OZEL 11.5 · 37063] Who may type into a member's shell right now.
 *
 * ## Why this is a state machine and not a boolean
 *
 * "The user took over the terminal" is not a flag that flips: an agent may be in
 * the middle of a command, and the user's keystrokes cannot be inserted into it.
 * Two writers on one PTY interleave on a single pending-callback slot — the same
 * reason `ExecutionCoordinator` serialises per shell — and the result is output
 * attributed to the wrong command, in both directions, with nothing in either
 * transcript to say so.
 *
 * So a takeover is a QUEUED REQUEST that is granted at a moment nobody is
 * mid-command, and while it is held the agent must not run one. That is four
 * states and two transitions, which is exactly small enough to test and too
 * important to leave as an `if`.
 *
 * ## The three rules it enforces
 *
 * 1. A request never pre-empts a running command. It waits, and the user sees
 *    "waiting for the current command" rather than a dead button.
 * 2. The agent cannot start a command while the request OR the hold is active —
 *    otherwise the takeover would be granted into a shell the agent immediately
 *    writes to, which is the failure the whole mechanism exists to avoid.
 * 3. Releasing is explicit and idempotent: a screen closing without releasing
 *    must not lock the member's shell for the rest of the run.
 */
class TerminalTakeover(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class State {
        /** Nobody asked. The agent owns the shell; the user watches. */
        AGENT,

        /** The user asked and is waiting for the agent's command to finish. */
        REQUESTED,

        /** The user owns the shell. The agent must not run anything. */
        USER,
    }

    data class Snapshot(
        val state: State,
        val requestedAtMs: Long = 0L,
        /** True when the user asked while a command was running. */
        val waitingForCommand: Boolean = false,
    )

    private var state = State.AGENT
    private var requestedAtMs = 0L
    private var requestedWhileBusy = false

    fun snapshot(agentBusy: Boolean): Snapshot = Snapshot(
        state = state,
        requestedAtMs = requestedAtMs,
        waitingForCommand = state == State.REQUESTED && agentBusy,
    )

    /**
     * The user asked for the shell.
     *
     * Granted at once when the agent is idle ([agentBusy] false), otherwise parked
     * in [State.REQUESTED] until [onAgentCommandFinished] says it is safe. Asking
     * twice is the same as asking once — a double tap must not queue two holds on
     * something that is refcount-free.
     */
    fun request(agentBusy: Boolean): State {
        if (state != State.AGENT) return state
        requestedAtMs = clock()
        requestedWhileBusy = agentBusy
        state = if (agentBusy) State.REQUESTED else State.USER
        return state
    }

    /**
     * The agent's running command finished. Grants a parked request.
     *
     * Returns true when this call is what granted it, so the caller can navigate
     * or show a one-shot message exactly once.
     */
    fun onAgentCommandFinished(): Boolean {
        if (state != State.REQUESTED) return false
        state = State.USER
        requestedWhileBusy = false
        return true
    }

    /** The user is done. Idempotent; safe to call from `DisposableEffect`. */
    fun release(): Boolean {
        if (state == State.AGENT) return false
        state = State.AGENT
        requestedWhileBusy = false
        requestedAtMs = 0L
        return true
    }

    /**
     * May the agent start a shell command now?
     *
     * False for BOTH [State.REQUESTED] and [State.USER]: a granted takeover that
     * the agent immediately writes into is not a takeover. The member's next turn
     * is what sees the refusal, so the text is written for it as well.
     */
    fun agentMayRun(): Boolean = state == State.AGENT

    /** What the agent is told when it tries anyway. */
    fun agentRefusal(): String = when (state) {
        State.AGENT -> ""
        State.REQUESTED ->
            "The user has asked to take over this terminal and is waiting for your current command " +
                "to finish. Do not start another shell command this turn — use file tools or the " +
                "browser, or continue next round."
        State.USER ->
            "The user is typing in this terminal right now, so the shell is theirs. Do not run a " +
                "command this turn; do your other work and try again next round, or ask them with " +
                "user_ask."
    }

    /** The label the member screen shows on its takeover button. */
    fun label(agentBusy: Boolean): String = when (state) {
        State.AGENT -> "Ele geçir"
        State.REQUESTED -> if (agentBusy) "Komut bitince…" else "Ele geçiriliyor"
        State.USER -> "Bırak"
    }

    /** True when `requestedAtMs` is older than [staleMs] and still parked. */
    fun isStalePark(agentBusy: Boolean, staleMs: Long): Boolean =
        state == State.REQUESTED && agentBusy && clock() - requestedAtMs >= staleMs

    /** True when the request was made while a command was already running. */
    fun wasParked(): Boolean = requestedWhileBusy
}
