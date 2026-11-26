package com.openminis.app.agent.team

/**
 * [37127 · Bağlantılar / ilişkiler] **Görev ↔ iddia bağları.**
 *
 * ## Bulgu
 *
 * Görev modeli bağlantıyı zaten taşıyordu — `TeamTask.runId`, `TeamTask.fromClaim`,
 * `TeamTask.createdBy` — ama bu üç alan **yalnız tek yönde** okunuyordu: görev
 * satırında "C3" yazıyordu. Ters yön yoktu: karşılaştırma odasında bir iddiaya
 * baktığında "bu iddiadan iş çıktı mı" görünmüyordu. Kullanıcı için önemli olan
 * tam olarak bu: **hangi tartışma işe dönüştü, hangisi havada kaldı.**
 *
 * ## Neden saf
 *
 * İki liste (iddialar, görevler) → tek ilişki tablosu. Ekranda hesaplanırsa
 * iki odada iki farklı cevap çıkar; bu depoda kınanan sınıf.
 */
object TaskLinks {

    /** One claim and the tasks that came out of it. */
    data class ClaimLink(
        val claimId: String,
        val claimText: String,
        val taskIds: List<String>,
    ) {
        val count: Int get() = taskIds.size
    }

    /**
     * Claims that produced at least one task, in claim order.
     *
     * A claim with no task is **not** returned — absence is rendered by the
     * caller ("bu iddiadan iş çıkmadı"), not by an empty row here. Returning an
     * empty row for every claim would bury the three that matter under nine
     * that do not.
     */
    fun claimLinks(
        claims: List<CouncilLiveRegistry.ClaimLine>,
        tasks: Collection<TeamTask>,
    ): List<ClaimLink> {
        if (claims.isEmpty() || tasks.isEmpty()) return emptyList()
        val byClaim = tasks
            .filter { it.fromClaim.isNotBlank() }
            .groupBy { normalise(it.fromClaim) }
        return claims.mapNotNull { c ->
            val hits = byClaim[normalise(c.id)] ?: return@mapNotNull null
            ClaimLink(
                claimId = c.id,
                claimText = c.text,
                taskIds = hits.sortedBy { it.createdAtMs }.map { it.id },
            )
        }
    }

    /** How many tasks reference [claimId]. Used where only the number is needed. */
    fun taskCountFor(claimId: String, tasks: Collection<TeamTask>): Int {
        val key = normalise(claimId)
        return tasks.count { it.fromClaim.isNotBlank() && normalise(it.fromClaim) == key }
    }

    /**
     * Tasks that carry no provenance (no run, no claim, no creator).
     *
     * These are not an error: the user can type a task by hand. But a task the
     * *model* opened with no provenance is a hole in the record, and the honest
     * way to show it is to count it, not to hide it.
     */
    fun orphans(tasks: Collection<TeamTask>): List<TeamTask> = tasks.filter {
        it.runId.isBlank() && it.fromClaim.isBlank() && it.createdBy.isBlank()
    }

    /** `C3` and `c3 ` are the same claim. Claim ids are case-insensitive upstream too. */
    private fun normalise(id: String): String = id.trim().lowercase()
}
