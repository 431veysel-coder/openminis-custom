package com.openminis.app.sandbox

/**
 * [MINIS-OZEL] Decides whether a toolchain group is present, partially present
 * or absent — as a pure function over the probe script's output.
 *
 * ## Why this is not just `command -v <one binary>`
 *
 * The first version of the toolchain screen classified a group by a single
 * binary: the "shell utilities" group (jq, sqlite, openssl, ripgrep, less,
 * tree, tmux, nano, vim) was considered installed when `jq` was. Two things
 * followed from that, and both were wrong:
 *
 *  - Installing `jq` alone through the free-form package field turned the whole
 *    group green, so the screen claimed nine tools the user did not have.
 *  - Uninstalling the group removed packages that were never there, and the
 *    screen could not distinguish "never installed" from "half installed".
 *
 * Reporting `Partial` with the missing names is the honest state, and it is
 * what a user needs in order to decide between "finish the install" and
 * "remove what is there".
 *
 * ## Why a separate object
 *
 * The parsing is the part that can be wrong without the screen ever showing it
 * — a silently mis-parsed line means a wrong icon, not an error. Keeping it
 * here, with no Android dependency, is what lets it be unit-tested
 * (ToolchainProbeTest).
 */
object ToolchainProbe {

    /** One group's required binaries, in display order. */
    data class Spec(val groupId: String, val binaries: List<String>)

    sealed interface Presence {
        data object Present : Presence
        /** Some binaries are missing; [missing] names them. */
        data class Partial(val missing: List<String>) : Presence
        data object Absent : Presence
    }

    /**
     * Builds ONE shell invocation that probes every group.
     *
     * A single round-trip on purpose: each `ExecutionCoordinator.execute` pays
     * the proot spawn cost, and 11 groups × 4 binaries as separate calls is
     * dozens of process spawns just to draw a list.
     *
     * Output format is `group:binary=yes` / `group:binary=no`, one per line.
     * Deliberately boring and stable — it is parsed by [parse].
     */
    fun buildScript(specs: List<Spec>): String = buildString {
        for (spec in specs) {
            for (binary in spec.binaries) {
                append("command -v ").append(binary).append(" >/dev/null 2>&1 ")
                append("&& echo '").append(spec.groupId).append(':').append(binary).append("=yes' ")
                append("|| echo '").append(spec.groupId).append(':').append(binary).append("=no'; ")
            }
        }
    }

    /**
     * Parses [buildScript]'s output into one [Presence] per group.
     *
     * Unknown group ids in the output are ignored rather than added: the script
     * is generated from the same list, so a stray id means the two drifted
     * apart, and inventing an entry would hide that.
     *
     * A group with no lines at all is [Presence.Absent] — the script emits one
     * line per binary, so silence means the shell never ran it. Reporting
     * "present" there is the failure mode this whole object exists to prevent.
     */
    fun parse(output: String, specs: List<Spec>): Map<String, Presence> {
        // groupId -> (binary -> found)
        val seen = LinkedHashMap<String, MutableMap<String, Boolean>>()
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim().trim('\'', '"')
            if (line.isEmpty()) continue
            val eq = line.lastIndexOf('=')
            if (eq <= 0) continue
            val left = line.substring(0, eq)
            val value = line.substring(eq + 1).trim()
            val colon = left.indexOf(':')
            if (colon <= 0) continue
            val groupId = left.substring(0, colon)
            val binary = left.substring(colon + 1)
            if (binary.isEmpty()) continue
            val found = when (value.lowercase()) {
                "yes", "true", "1" -> true
                "no", "false", "0" -> false
                else -> continue
            }
            seen.getOrPut(groupId) { LinkedHashMap() }[binary] = found
        }

        return specs.associate { spec ->
            val results = seen[spec.groupId]
            val presence = when {
                results == null -> Presence.Absent
                spec.binaries.all { results[it] == true } -> Presence.Present
                spec.binaries.none { results[it] == true } -> Presence.Absent
                else -> Presence.Partial(spec.binaries.filter { results[it] != true })
            }
            spec.groupId to presence
        }
    }

    /**
     * Whether an install should be considered successful: every binary present.
     *
     * Used after `apk add` instead of the exit code, because apk can exit 0
     * while a package failed to land (a repository hiccup, a dependency
     * conflict resolved by skipping). The tool existing afterwards is the only
     * claim worth making.
     */
    fun isComplete(presence: Presence?): Boolean = presence is Presence.Present
}
