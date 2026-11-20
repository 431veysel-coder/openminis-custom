package com.openminis.app.agent.team

/**
 * [MINIS-OZEL 11.5 · 37062] How many sub agents a whole council run may have in
 * flight at once.
 *
 * ## The arithmetic this exists to stop
 *
 * `AgentJobRegistry.MAX_CONCURRENT_CHILD_JOBS_PER_PARENT = 3` is counted PER
 * PARENT SESSION. A council member is its own session, so it is a parent as far
 * as the registry is concerned — which means five members each starting three
 * sub agents is **fifteen concurrent agent loops**, every one of them a
 * `ChatViewModel` with a tool belt, plus up to fifteen browsers. The registry
 * would report every single one as within limits, because each parent really
 * does have three.
 *
 * So the budget is per RUN, and it lives here as arithmetic rather than as a
 * counter hidden in a registry: the whole policy fits in one screen and can be
 * tested without a device.
 *
 * ## Why "budget full" becomes a task, not an error
 *
 * The user asked for every member to have its own sub agents, and this class is
 * what makes that affordable. A refusal that just fails teaches the member
 * nothing and it retries next round; a refusal that says "write it on your task
 * list" turns the pressure into a queue the council can actually process
 * (`assign_task` / the position's `tasks[]`). That is the only trade that
 * satisfies both "every member can delegate" and "the phone stays alive".
 */
class RunSubAgentBudget(
    val slots: Int,
    /**
     * Total starts allowed for the run, not a concurrent limit. A run that
     * launches six short sub agents in six different rounds is fine; one that
     * launches forty is a runaway loop wearing a delegation costume.
     */
    val maxLaunches: Int = MAX_LAUNCHES,
) {
    init {
        require(slots >= 1) { "a run must be able to start at least one sub agent" }
    }

    /** May a new sub agent start right now, given [active] already running? */
    fun canStart(active: Int): Boolean = active < slots

    /** May the run start one more at all, given [launched] already started? */
    fun canLaunch(launched: Int): Boolean = launched < maxLaunches

    /** Both gates, in the order a caller should ask them. */
    fun admit(active: Int, launched: Int): Boolean = canLaunch(launched) && canStart(active)

    /**
     * The refusal text. It names the counting rule, because a member told only
     * "not allowed" will try a different `agent` name and blame that.
     */
    fun refusal(active: Int, launched: Int): String = when {
        !canLaunch(launched) ->
            "The council run has already started $launched sub agents (limit $maxLaunches), so this " +
                "one was not started. Do the work with your own tools, or put it on your task list " +
                "with assign_task so a later round can pick it up."
        else ->
            "This council run already has $active sub agents running (limit $slots at once), so this " +
                "one was not started. Write it on your task list with assign_task — the council will " +
                "pick it up when a slot frees — or do it yourself."
    }

    companion object {
        /** Starts per run. Twelve covers "each of five members delegates twice". */
        const val MAX_LAUNCHES = 12

        /**
         * Slots for a device class, in the same shape as [CouncilPolicy]:
         * two on a low-RAM phone, three on a typical one, four when there is room.
         */
        fun forDevice(isLowRamDevice: Boolean, totalRamMb: Int): RunSubAgentBudget = when {
            isLowRamDevice || totalRamMb in 1 until CouncilPolicy.SMALL_DEVICE_RAM_MB ->
                RunSubAgentBudget(slots = 2)
            totalRamMb >= CouncilPolicy.LARGE_DEVICE_RAM_MB ->
                RunSubAgentBudget(slots = 4)
            else -> RunSubAgentBudget(slots = 3)
        }

        /**
         * A budget that admits nothing, for a run whose device class the caller
         * did not measure. Never used to *deny by accident* at the call site: it
         * is the value `AndroidCouncilRuntime` replaces with [forDevice] before a
         * single member turn, and its presence makes "nobody set a budget" a
         * visible configuration error instead of an unlimited one.
         */
        val DISABLED = RunSubAgentBudget(slots = 1, maxLaunches = 0)
    }
}
