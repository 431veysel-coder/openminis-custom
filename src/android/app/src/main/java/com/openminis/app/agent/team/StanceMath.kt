package com.openminis.app.agent.team

import kotlin.math.ceil

/**
 * [MINIS-OZEL 11.5 · 37037] The stopping rule, as arithmetic.
 *
 * The single most important decision in this design: **a model may not decide
 * that the council has converged.** Every "are we done?" judgement made by a
 * model in a loop is a loop that can be talked into ending early — and in a
 * council it is worse than in a handoff run, because the same model that says
 * "they agree" is usually the one whose claim is being agreed with.
 *
 * So the evaluator is pure: a [ClaimMatrix] in, a [Consensus] out, no
 * suspend, no provider, no clock. Which means the stopping behaviour can be
 * tested exhaustively (see `CouncilStanceMathTest`), and a threshold change is a
 * one-line diff with a failing test, not a behavioural mystery.
 *
 * ## The thresholds, and why these numbers
 *
 * - Majority is ⌈0.6·N⌉, not ⌈N/2⌉. With five members, 3/5 = 0.60 decides and
 *   2/5 does not; with three members it is 2/3, with four it is 3/4. A bare
 *   half means a 2–2 tie "decides" whenever rounding lands the wrong way.
 * - "Too many unsure" is ⌈0.5·N⌉. Half the council being undecided is not a
 *   majority for anything, and treating unsure as a soft yes is the exact
 *   behaviour that makes multi-model output feel confident and useless.
 * - Stability is ≤ 1 changed cell for two consecutive rounds ([CouncilBudget]).
 *   One cell is noise (a model rephrasing its stance); two rounds is a trend.
 */
object StanceMath {

    /** A side needs at least this share of the participants to carry a claim. */
    const val MAJORITY_SHARE = 0.6

    /** At or above this share of "not sure", the claim is unresolved. */
    const val UNSURE_SHARE = 0.5

    fun evaluate(
        matrix: ClaimMatrix,
        previous: ClaimMatrix? = null,
        attendees: Int = 0,
        stableStreak: Int = 0,
        budget: CouncilBudget = CouncilBudget(),
    ): Consensus {
        // The participant count comes from the round, not from the matrix: a
        // chair that drops a claim would otherwise shrink the denominator and
        // turn one supporter into a majority.
        val n = if (attendees > 0) attendees else (matrix.claims.maxOfOrNull { it.voted } ?: 0)
        val majority = if (n <= 0) Int.MAX_VALUE else ceil(MAJORITY_SHARE * n).toInt()
        val unsureLimit = if (n <= 0) Int.MAX_VALUE else ceil(UNSURE_SHARE * n).toInt()

        val verdicts = matrix.claims.map { claim ->
            ClaimVerdict(claim, verdictOf(claim, majority, unsureLimit))
        }

        val changed = previous?.let { cellsChanged(it, matrix) } ?: -1
        val streak = if (changed in 0..budget.stabilityThreshold) stableStreak + 1 else 0

        return Consensus(
            verdicts = verdicts,
            cellsChanged = changed,
            stableStreak = streak,
            attendees = n,
        )
    }

    private fun verdictOf(claim: Claim, majority: Int, unsureLimit: Int): Verdict {
        if (claim.voted == 0) return Verdict.ORPHAN
        // Checked before the majority branches, and it can never cancel one out:
        // unsureLimit (0.5N) plus majority (0.6N) already exceed N, so a side
        // that reached the majority cannot also have half the council unsure.
        // What this DOES change is the label: 1 support + 1 oppose + 3 unsure
        // (n=5) is UNRESOLVED — "nobody knows yet" — rather than CONTESTED,
        // which would read as a live disagreement and invite another round for
        // no reason.
        if (claim.uncertain >= unsureLimit) return Verdict.UNRESOLVED
        if (claim.support > 0 && claim.oppose > 0) {
            return if (claim.support >= majority || claim.oppose >= majority) {
                Verdict.DECIDED
            } else {
                Verdict.CONTESTED
            }
        }
        if (claim.oppose == 0 && claim.uncertain == 0 && claim.support == claim.voted) {
            return Verdict.UNANIMOUS
        }
        if (claim.support == 0 && claim.uncertain == 0 && claim.oppose == claim.voted) {
            // Every participant rejects the claim. That IS agreement — agreeing
            // that the proposal is wrong. Calling it UNANIMOUS would then mark
            // the claim as accepted, so it stays DECIDED with a zero support
            // count and the synthesiser reads the direction from the numbers.
            return Verdict.DECIDED
        }
        return if (claim.support >= majority || claim.oppose >= majority) {
            Verdict.DECIDED
        } else {
            Verdict.CONTESTED
        }
    }

    /**
     * How many (claim, member) pairs moved between two rounds.
     *
     * Counting per CLAIM TEXT rather than per id is deliberate: chairs reorder
     * and renumber claims between rounds, and an id-keyed diff would report
     * movement every round purely from renumbering — a council that would then
     * never look stable, and never stop.
     */
    fun cellsChanged(previous: ClaimMatrix, current: ClaimMatrix): Int {
        val before = previous.claims.associate { normalise(it.text) to it.stances }
        var changed = 0
        var matchedClaims = 0
        for (claim in current.claims) {
            val key = normalise(claim.text)
            val old = before[key]
            if (old == null) {
                // A claim nobody made before is new information, not movement.
                // It still counts as one change so a council that keeps
                // inventing claims cannot claim stability.
                changed += 1
                continue
            }
            matchedClaims++
            val members = old.keys + claim.stances.keys
            for (m in members) {
                if (old[m] != claim.stances[m]) changed++
            }
        }
        // Claims that disappeared count as one change each: dropping the claim
        // under dispute is the cheapest way to look converged.
        changed += (before.keys - current.claims.map { normalise(it.text) }.toSet()).size
        // A round where the matrix merely got shorter is not stability.
        if (matchedClaims == 0 && current.claims.isNotEmpty()) changed = maxOf(changed, 1)
        return changed
    }

    private fun normalise(text: String): String =
        text.lowercase().replace(Regex("""[^\p{L}\p{N} ]"""), "").replace(Regex("""\s+"""), " ").trim()

    /**
     * The dissent block of the report: every claim that did not come out
     * unanimous, with the opponent's own words.
     *
     * This exists because the alternative — a summary that mentions objections
     * in the summariser's phrasing — is how a council's minority view gets
     * smoothed into "some concerns were raised". A dissenting member's sentence
     * is copied, not paraphrased, and the caller passes it through untouched.
     */
    fun dissent(consensus: Consensus, positions: List<Position>): List<String> {
        val byMember = positions.associateBy { it.member }
        val out = mutableListOf<String>()
        for (verdict in consensus.verdicts) {
            if (verdict.verdict == Verdict.UNANIMOUS) continue
            val claim = verdict.claim
            val opponents = claim.stances.filterValues { it != Stance.SUPPORT }.keys
            for (member in opponents) {
                val position = byMember[member]
                // Prefer the member's own sentence if it can be found; fall back
                // to the stance so the report still says who disagreed.
                val quote = position?.points
                    ?.firstOrNull { it.stance != Stance.SUPPORT && it.hasSource && it.text.isNotBlank() }
                    ?.text
                    ?: position?.points?.firstOrNull { it.stance != Stance.SUPPORT && it.text.isNotBlank() }?.text
                    ?: "(konum metni yok)"
                out += "${member} — ${claim.id}: $quote"
            }
        }
        return out.distinct().take(12)
    }
}
