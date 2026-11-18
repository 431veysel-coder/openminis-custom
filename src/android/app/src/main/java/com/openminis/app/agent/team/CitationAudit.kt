package com.openminis.app.agent.team

import java.io.File

/**
 * [MINIS-OZEL 11.5 · 37037] Layer 1 of the three-layer check: the one that does
 * not involve a model at all.
 *
 * ## The problem
 *
 * A synthesised answer cites `[E7]`. If `E7` was never on the board — the model
 * remembered an id from an earlier round, or invented a plausible one — the
 * sentence reads exactly like a sourced claim. No model-based check catches
 * this reliably, because the reviewing model has the same tendency to find the
 * citation plausible. Counting is the only defence that does not share the
 * failure mode.
 *
 * ## What it checks
 *
 *  1. Every `[E…]` in the report exists on the board.
 *  2. A report that cites nothing at all when the board is not empty is
 *     flagged — five models researched for four rounds and the answer has no
 *     sources is a fact worth seeing.
 *  3. Paths the report claims to have written (`/var/minis/...`) exist.
 *  4. Evidence marked `unverified` (a URL a member typed instead of a URL a tool
 *     fetched) is named, so a report cannot launder a made-up source by citing it.
 *
 * It deliberately does NOT check that a quote supports its sentence. That is a
 * reading task, and it is [CouncilVerifier]'s job, not arithmetic dressed up as
 * verification.
 */
object CitationAudit {

    /**
     * One row of the shared board.
     *
     * [unverified] is the distinction that matters most in this whole file: a URL
     * that came out of `browser_use` was actually fetched, a URL a member typed
     * from memory was not. Both look identical in a citation.
     */
    data class Evidence(
        val id: String,
        val url: String = "",
        val quote: String = "",
        val member: String = "",
        val round: Int = 0,
        val unverified: Boolean = true,
        val title: String = "",
    )

    data class Result(
        val passed: Boolean,
        val problems: List<String>,
        val citedIds: List<String> = emptyList(),
        val unknownIds: List<String> = emptyList(),
        val missingPaths: List<String> = emptyList(),
        val unverifiedIds: List<String> = emptyList(),
    ) {
        /** One line for the report header; never says "verified" for a partial pass. */
        fun headline(): String = when {
            passed && citedIds.isEmpty() -> "atıf yok"
            passed -> "atıf ✓ (${citedIds.size})"
            else -> "atıf ✗ (${problems.size})"
        }
    }

    private val CITATION = Regex("""\[(E\d{1,4})\]""")
    private val PATH = Regex("""(/var/minis/[\w./\-]+|minis://[\w./\-]+)""")

    fun audit(
        report: String,
        board: List<Evidence>,
        /** Root for relative path checks; null disables the filesystem check (tests). */
        root: File? = null,
    ): Result {
        val problems = mutableListOf<String>()
        val known = board.associateBy { it.id.uppercase() }
        val cited = CITATION.findAll(report).map { it.groupValues[1].uppercase() }.distinct().toList()
        val unknown = cited.filter { it !in known }

        if (unknown.isNotEmpty()) {
            problems += "Tahtada olmayan kaynak: ${unknown.joinToString(", ")}"
        }
        if (board.isNotEmpty() && cited.isEmpty()) {
            problems += "Raporda hiç kaynak atfı yok (tahtada ${board.size} kayıt var)."
        }
        val unverified = cited.mapNotNull { known[it] }.filter { it.unverified }.map { it.id }
        if (unverified.isNotEmpty()) {
            problems += "Doğrulanmamış kaynak: ${unverified.joinToString(", ")} (araçla çekilmedi)"
        }

        val missingPaths = mutableListOf<String>()
        if (root != null) {
            val paths = PATH.findAll(report).map { it.value.trimEnd('.', ',', ')', '‘', '’') }.distinct()
            for (path in paths) {
                if (path.startsWith("minis://")) continue
                if (!File(path).exists()) missingPaths += path
            }
            if (missingPaths.isNotEmpty()) {
                problems += "Raporda geçen ama var olmayan dosya: ${missingPaths.joinToString(", ")}"
            }
        }

        return Result(
            passed = problems.isEmpty(),
            problems = problems,
            citedIds = cited,
            unknownIds = unknown,
            missingPaths = missingPaths,
            unverifiedIds = unverified,
        )
    }
}
