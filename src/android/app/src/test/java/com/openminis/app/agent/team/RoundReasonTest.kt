package com.openminis.app.agent.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F1] Tur gerekçesi testleri.
 *
 * Sınanan asıl şey: gerekçe **karar vermiyor**, kararı **açıklıyor**. Bir
 * gün karar `StanceMath`'te değişip burası değişmezse, ekran yanlış sebep
 * gösterir — bu yüzden "hangi iddia açık" sorusunun cevabı `Verdict`'ten
 * türetiliyor, yeniden hesaplanmıyor.
 */
class RoundReasonTest {

    private fun claim(
        id: String,
        support: Int,
        oppose: Int,
        uncertain: Int,
        verdict: Verdict,
    ) = ClaimVerdict(
        Claim(
            id = id,
            text = "iddia $id",
            stances = buildMap {
                repeat(support) { put("s$id-$it", Stance.SUPPORT) }
                repeat(oppose) { put("o$id-$it", Stance.OPPOSE) }
                repeat(uncertain) { put("u$id-$it", Stance.UNCERTAIN) }
            },
        ),
        verdict,
    )

    private fun consensus(vararg verdicts: ClaimVerdict, changed: Int = 5, streak: Int = 0) =
        Consensus(verdicts = verdicts.toList(), cellsChanged = changed, stableStreak = streak, attendees = 4)

    @Test
    fun `open claims are the ones that are neither decided nor unanimous`() {
        val c = consensus(
            claim("C1", 3, 1, 0, Verdict.DECIDED),
            claim("C2", 2, 2, 0, Verdict.CONTESTED),
            claim("C3", 1, 0, 3, Verdict.UNRESOLVED),
            claim("C4", 4, 0, 0, Verdict.UNANIMOUS),
            claim("C5", 0, 0, 0, Verdict.ORPHAN),
        )
        val next = RoundReason.next(c, CouncilBudget(maxRounds = 4), nextRound = 2, attendees = 4)
        assertEquals(listOf("C3", "C2", "C5"), next!!.open.map { it.id })
    }

    @Test
    fun `open claims sort by resistance, not by id`() {
        val c = consensus(
            claim("C1", 0, 1, 1, Verdict.CONTESTED),
            claim("C2", 0, 3, 1, Verdict.CONTESTED),
            claim("C3", 0, 0, 1, Verdict.UNRESOLVED),
        )
        val next = RoundReason.next(c, CouncilBudget(maxRounds = 4), 2, 4)!!
        assertEquals(listOf("C2", "C1", "C3"), next.open.map { it.id })
    }

    @Test
    fun `no next round when the round cap is already reached`() {
        // Karar çağıranda: döngü `nextRound > maxRounds` ise zaten durmuştur.
        // Gerekçe üretmek, olmayan bir turu duyurmak olurdu.
        val c = consensus(claim("C1", 2, 2, 0, Verdict.CONTESTED))
        assertNull(RoundReason.next(c, CouncilBudget(maxRounds = 3), nextRound = 4, attendees = 4))
    }

    @Test
    fun `all decided means no open claim but a reason still exists`() {
        // Bu gerçek bir durum: hücreler değişmeye devam ediyor (streak 0) ama
        // hiçbir iddia açık değil. Gerekçe "hareket var" olur — `hasOpen`
        // false ve arayüz bunu ayrı cümleyle yazar.
        val c = consensus(claim("C1", 3, 1, 0, Verdict.DECIDED), changed = 7, streak = 0)
        val next = RoundReason.next(c, CouncilBudget(maxRounds = 4), 3, 4)!!
        assertFalse(next.hasOpen)
        assertEquals(7, next.cellsChanged)
        assertEquals(0, next.stableStreak)
        assertEquals(3, next.nextRound)
    }

    @Test
    fun `threshold is carried so the screen can print it`() {
        val c = consensus(claim("C1", 2, 2, 0, Verdict.CONTESTED))
        val next = RoundReason.next(c, CouncilBudget(maxRounds = 4), 2, 4)!!
        // StanceMath.MAJORITY_SHARE = 0.6 → %60. Ekranda "eşik %60" yazar;
        // bu sayı burada türetilmezse iki yerde iki farklı eşik doğar.
        assertEquals(60, next.majorityPercent)
        assertEquals(4, next.attendees)
    }

    @Test
    fun `zero attendees falls back to what the consensus recorded`() {
        val c = consensus(claim("C1", 2, 2, 0, Verdict.CONTESTED))
        val next = RoundReason.next(c, CouncilBudget(maxRounds = 4), 2, attendees = 0)!!
        assertTrue(next.attendees > 0)
    }
}
