package com.openminis.app.agent.team

/**
 * [MINIS-OZEL 11.5 · 37037] How deep the council goes on THIS device.
 *
 * The user's call was "by device", and this class is the whole of that rule. It
 * exists as a separate object because the decision must be:
 *
 *  - **explainable**: every preset carries the sentence the UI shows ("cihaz 4 GB
 *    → 3 üye"), so the user can see why the council is the size it is instead of
 *    wondering why five models turned into three;
 *  - **overridable**: a preset is a default, and [CouncilBudget] passed in from a
 *    team definition always wins. A phone is not a better judge of how much money
 *    the user wants to spend than the user is;
 *  - **testable**: no `Context`, no `ActivityManager` — the caller measures, this
 *    decides.
 *
 * ## The numbers, and what they are based on
 *
 * One live member is a child `ChatViewModel` with its own agent loop: measured
 * 30–50 MB with history. One browser tab is 40–90 MB. So:
 *
 * | device | members | wave | rounds | why |
 * |---|---|---|---|---|
 * | low RAM (`isLowRamDevice` or < 4 GB) | 3 | 2 | 2 | 2 members + 2 tabs ≈ 200 MB, safe on a 3 GB phone |
 * | typical (4–7 GB) | 3 | 3 | 3 | three at once ≈ 300 MB + the app's own session |
 * | large (≥ 8 GB) | 5 | 4 | 4 | four at once, one stream slot left for the user |
 *
 * Five members on a 4 GB phone is not a smaller version of five members on a
 * 12 GB phone: the child VMs get evicted mid-round by `onTrimMemory`, members
 * come back `absent`, and the council reports "no consensus" for a reason that
 * has nothing to do with the models.
 *
 * ## The cap this is actually sized against
 *
 * `SessionConcurrencyManager.MAX_CONCURRENT = 6` — six agent loops may STREAM at
 * once, process-wide, and a seventh waits in a queue with no timeout. A council
 * member is a full loop and takes a slot; sub agents do not (they are capped
 * separately per parent). So a wave is not bounded by member count but by
 * "how many of the six am I willing to take": one is reserved for the user's own
 * chats, which is why the large-device wave is 4 and not 5. The council run also
 * releases the PARENT's slot while it blocks (37062); until that ships, a wave of
 * 5 plus a blocked parent fills all six and starves every other session.
 */
object CouncilPolicy {

    data class Preset(
        val budget: CouncilBudget,
        val label: String,
        val reason: String,
    )

    /** Threshold below which a device is treated as small, in MB of total RAM. */
    const val SMALL_DEVICE_RAM_MB = 4096

    /** Threshold at which five members are safe, in MB of total RAM. */
    const val LARGE_DEVICE_RAM_MB = 8192

    fun forDevice(
        isLowRamDevice: Boolean,
        totalRamMb: Int,
        userPinnedMembers: Int? = null,
    ): Preset {
        val preset = when {
            isLowRamDevice || totalRamMb in 1 until SMALL_DEVICE_RAM_MB -> Preset(
                budget = CouncilBudget(maxRounds = 2, waveSize = 2),
                label = "Hafif",
                reason = if (isLowRamDevice) {
                    "Sistem bu cihazı düşük RAM'li sayıyor → 2'şerli dalga, 2 tur."
                } else {
                    "Cihaz ${totalRamMb} MB RAM → 2'şerli dalga, 2 tur."
                },
            )
            totalRamMb >= LARGE_DEVICE_RAM_MB -> Preset(
                // 4, not 5: MAX_CONCURRENT is 6 process-wide and one slot stays
                // for the user's own sessions. A 5-member wave is allowed only
                // when the caller passes a budget explicitly.
                budget = CouncilBudget(maxRounds = 4, waveSize = 4),
                label = "Derin",
                reason = "Cihaz ${totalRamMb} MB RAM → 4 üye aynı anda, 4 tur " +
                    "(1 akış yuvası diğer sohbetler için ayrıldı).",
            )
            else -> Preset(
                budget = CouncilBudget(maxRounds = 3, waveSize = 3),
                label = "Dengeli",
                reason = "Cihaz ${totalRamMb} MB RAM → 3'erli dalga, 3 tur.",
            )
        }
        if (userPinnedMembers == null) return preset
        // A team definition may ask for fewer members than the device can host;
        // it may not ask for more than the wave can carry. Raising the wave to
        // fit a pinned team is refused here and reported, not silently clamped.
        return if (userPinnedMembers <= preset.budget.waveSize) {
            preset
        } else {
            preset.copy(
                label = "${preset.label} (sınırlı)",
                reason = preset.reason + " Ekip $userPinnedMembers üye istiyor; " +
                    "dalga ${preset.budget.waveSize} ile sırayla çalışacak.",
                budget = preset.budget,
            )
        }
    }

    /**
     * Waves for [memberCount] at [waveSize]. Chunked rather than a semaphore on
     * purpose: a round's members must be started in a KNOWN order so a test can
     * assert the wave shape, and the screen can show "2/2 dalga" before the
     * second wave starts instead of watching a counter that never settles.
     */
    fun waves(memberCount: Int, waveSize: Int): List<IntRange> {
        if (memberCount <= 0) return emptyList()
        val size = waveSize.coerceIn(1, CouncilBudget.MAX_WAVE)
        val out = mutableListOf<IntRange>()
        var start = 0
        while (start < memberCount) {
            val end = minOf(start + size, memberCount) - 1
            out += start..end
            start = end + 1
        }
        return out
    }

    fun waveCount(memberCount: Int, waveSize: Int): Int = waves(memberCount, waveSize).size
}
