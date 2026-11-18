package com.openminis.app.agent.team

/**
 * [MINIS-OZEL 11.5 · 37065] How many questions a member may ask, and how often.
 *
 * ## Why a counter outside the member
 *
 * The counters belong to (run, member) and must survive across the member's
 * turns, because "three per round" is only meaningful if round boundaries are
 * visible to it — and a member's turn cannot see rounds. Keeping them here, keyed
 * by the run, also means a runaway member cannot reset its own allowance by
 * opening a new session.
 *
 * ## Why the per-round counter is reset by the LOOP, not by a timer
 *
 * A timer would be wrong in both directions: a long round would give a member a
 * fresh allowance mid-round, and a fast round would deny it one it never used.
 * [resetRound] is called by whoever knows a round started — the run, not the tool.
 *
 * ## The dedupe set
 *
 * Asking the same question twice is the retry path (a model re-emitting a call
 * after a confusing result), not a second question. Deduping within the round is
 * what stops a member from spending its whole allowance on one rephrase.
 */
object AskTeammateQuota {

    private data class Key(val runId: String, val member: String)

    private data class Counters(
        var round: Int = 0,
        var run: Int = 0,
        /** recent questions this round: "peer|normalised question" */
        val asked: MutableSet<String> = mutableSetOf(),
        /** [37051] Tasks this member handed out this round. */
        var tasksRound: Int = 0,
    )

    private val counters = HashMap<Key, Counters>()

    fun askedThisRound(runId: String, member: String): Int = synchronized(this) {
        counters[Key(runId, member)]?.round ?: 0
    }

    fun askedThisRun(runId: String, member: String): Int = synchronized(this) {
        counters[Key(runId, member)]?.run ?: 0
    }

    /**
     * Count one question and remember it. Returns false when the identical
     * question was already asked this round (in which case nothing is counted).
     */
    fun noteQuestion(runId: String, member: String, peer: String, normalised: String): Boolean =
        synchronized(this) {
            val key = Key(runId, member)
            val c = counters.getOrPut(key) { Counters() }
            val dedupe = "${peer.lowercase()}|$normalised"
            if (!c.asked.add(dedupe)) return false
            c.round += 1
            c.run += 1
            true
        }

    /**
     * [37051] Tasks one member has handed to colleagues this round.
     *
     * Kept in the same counters as the questions because both are "how much can
     * one member spend talking to the others in one round", and splitting them
     * into two registries would let a member alternate between them to get double
     * the allowance.
     */
    fun tasksAssignedThisRound(runId: String, member: String): Int = synchronized(this) {
        counters[Key(runId, member)]?.tasksRound ?: 0
    }

    /** [37051] Count one hand-over. */
    fun noteTaskAssigned(runId: String, member: String) {
        synchronized(this) {
            counters.getOrPut(Key(runId, member)) { Counters() }.tasksRound += 1
        }
    }

    /** [37065] Called by the run when a round starts: a fresh per-round allowance. */
    fun resetRound(runId: String) {
        synchronized(this) {
            counters.keys.filter { it.runId == runId }.forEach { key ->
                counters[key]?.let {
                    it.round = 0
                    it.tasksRound = 0
                    it.asked.clear()
                }
            }
        }
    }

    /** Drop a run's counters. Called when the run ends, whatever the outcome. */
    fun forgetRun(runId: String) {
        synchronized(this) {
            // removeAll on a live keys() view is fine here because nothing else
            // is touching the map inside this block, but collecting first is the
            // habit that survives a later change to a concurrent map.
            counters.keys.filter { it.runId == runId }.forEach { counters.remove(it) }
        }
    }

    /** Tests only: clear everything. */
    fun clearAll() {
        synchronized(this) { counters.clear() }
    }
}
