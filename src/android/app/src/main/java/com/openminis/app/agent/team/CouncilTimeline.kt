package com.openminis.app.agent.team

/**
 * [37131 · Ofis] **Koşunun zaman çizgisi** — sanal ofisin "Kayıt" görünümü.
 *
 * ## Neden sınırlı
 *
 * Bu özelliğin kendi bellek riski burada. Bir koşu 3 tur × 5 üye × 10 olay
 * üretebilir; uzun bir koşuda bu yüzlerce satır. **Sınırsız** bir liste, koşu
 * uzadıkça büyüyen bir sızıntıdır ve tam olarak "bellek dolması" şikâyetinin
 * kaynağı olurdu. `MAX_LINES` ile halka tampon gibi davranır: en eski satır
 * düşer, bellek sabit kalır.
 *
 * ## Neden kırpma **sessiz değil**
 *
 * `dropped` sayacı tutulur ve arayüz "en eski N olay gösterilmiyor" der.
 * Sessiz kırpma, kullanıcıya eksik bir kaydı tam göstermek olurdu — bu depoda
 * kınanan sınıf. Kayıp **görünür**.
 *
 * ## Neden satır tipi ayrı
 *
 * `CouncilEvent` bir olay; satır bir **cümle**. Zaman + üye + ne oldu. Olay
 * tipini UI'a taşımak, her yeni olay tipinde ekranı değiştirmek demek.
 */
object CouncilTimeline {

    /** Ring size. 500 satır ≈ bir uzun koşunun tamamı, sabit bellek. */
    const val MAX_LINES = 500

    /** One rendered line of the office transcript. */
    data class Line(
        val atMs: Long,
        /** Member name, or empty for run-level events (round start, synthesis…). */
        val who: String,
        /**
         * Machine key for the event kind — the screen maps it to a localized
         * word. Deliberately NOT a sentence: the core does not know the
         * language (this depoda bir kez ihlal edildi, `.28`).
         */
        val kind: String,
        /** Free text the event carried, already shortened. Empty when none. */
        val detail: String,
    )

    data class State(
        val lines: List<Line> = emptyList(),
        /** How many lines fell off the front. Shown, never hidden. */
        val dropped: Int = 0,
    ) {
        val truncated: Boolean get() = dropped > 0
    }

    /**
     * Append one event. Returns a NEW state; the caller owns the StateFlow.
     *
     * `atMs == 0` (unstamped, i.e. a test-built event) is dropped rather than
     * given an invented time. A transcript with a wrong clock is worse than a
     * transcript with a gap: the gap is visible, the wrong time is not.
     */
    fun append(state: State, e: CouncilEvent): State {
        val line = toLine(e) ?: return state
        val next = state.lines + line
        return if (next.size <= MAX_LINES) {
            State(next, state.dropped)
        } else {
            State(next.takeLast(MAX_LINES), state.dropped + (next.size - MAX_LINES))
        }
    }

    /** Map one event to a line, or null when it carries nothing to show. */
    fun toLine(e: CouncilEvent): Line? {
        if (e.atMs == 0L) return null
        return when (e) {
            is CouncilEvent.Started -> Line(
                e.atMs, "", "started",
                "${e.members.size} · " + e.task.take(120),
            )
            is CouncilEvent.RoundStarted -> Line(e.atMs, "", "round_started", e.round.toString())
            is CouncilEvent.Positioned -> Line(
                e.atMs, e.member, if (e.absent) "absent" else "position", e.claim.take(160),
            )
            is CouncilEvent.Canonicalized -> Line(
                e.atMs, "", "canonicalized", "${e.claims} · ${e.dropped}",
            )
            is CouncilEvent.ConsensusReached -> Line(
                e.atMs, "", "consensus", e.cellsChanged.toString(),
            )
            is CouncilEvent.StableRound -> Line(e.atMs, "", "stable", e.streak.toString())
            is CouncilEvent.Synthesized -> Line(e.atMs, "", "synthesized", e.chars.toString())
            is CouncilEvent.Audited -> Line(
                e.atMs, "", if (e.passed) "audited_ok" else "audited_fail",
                e.problems.firstOrNull()?.take(120).orEmpty(),
            )
            is CouncilEvent.Finished -> Line(e.atMs, "", "finished", e.stoppedBy.name)
        }
    }

    /**
     * Lines one member produced — the "who talks how much" view.
     *
     * Empty [who] (run-level events) is excluded: counting them per member
     * would credit the chair's work to nobody and make the totals lie.
     */
    fun perMember(state: State): Map<String, Int> = state.lines
        .filter { it.who.isNotBlank() }
        .groupingBy { it.who }
        .eachCount()

    /** Lines of one kind, for the filter chips. */
    fun ofKind(state: State, kind: String): List<Line> =
        state.lines.filter { it.kind == kind }

    /** Kinds present in this run, in first-appearance order — what to offer as filters. */
    fun kinds(state: State): List<String> =
        state.lines.map { it.kind }.distinct()
}
