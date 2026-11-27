package com.openminis.app.agent.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [H-019] Konsey özeti testleri.
 *
 * Asıl sınanan şey **karar vermemesi**: özet, `verdict` alanında yazılı olanı
 * sayar. Yeniden karar verirse raporla çelişen ikinci bir doğruluk doğar.
 */
class CouncilDigestTest {

    private fun stat(
        id: String,
        support: Int = 0,
        oppose: Int = 0,
        uncertain: Int = 0,
        verdict: String = "decided",
    ) = CouncilDigest.Stat(id, "iddia $id", support, oppose, uncertain, verdict)

    @Test
    fun `settled and open counts come from the verdict, not from the numbers`() {
        // Sayı 3'e 1 ama verdict "contested": karar tek yerde verilmiş,
        // özet ona uyar.
        val s = CouncilDigest.of(
            claims = listOf(
                stat("C1", 3, 0, 0, "unanimous"),
                stat("C2", 3, 1, 0, "contested"),
                stat("C3", 0, 0, 0, "orphan"),
            ),
            participants = 4,
            roundsUsed = 2,
            roundsPlanned = 3,
        )
        assertEquals(3, s.total)
        assertEquals(1, s.unanimous)
        assertEquals(0, s.decided)
        assertEquals(2, s.settled)
        assertTrue(s.hasOpen)
        assertEquals(listOf("C2", "C3"), s.open.map { it.id })
    }

    @Test
    fun `open claims sort by how much resistance they drew`() {
        val s = CouncilDigest.of(
            claims = listOf(
                stat("C1", 1, 1, 0, "contested"),
                stat("C2", 0, 3, 2, "contested"),
                stat("C3", 0, 0, 1, "unresolved"),
            ),
            participants = 5, roundsUsed = 1, roundsPlanned = 0,
        )
        assertEquals(listOf("C2", "C1", "C3"), s.open.map { it.id })
    }

    @Test
    fun `orphan claims are also listed as unvoted`() {
        val s = CouncilDigest.of(
            claims = listOf(stat("C1", 2, 0, 0, "unanimous"), stat("C9", 0, 0, 0, "orphan")),
            participants = 2, roundsUsed = 1, roundsPlanned = 1,
        )
        assertEquals(listOf("C9"), s.unvoted.map { it.id })
        assertEquals("1/1", s.roundsLabel)
    }

    @Test
    fun `no plan means rounds label is just the number`() {
        val s = CouncilDigest.of(emptyList(), 0, 4, 0)
        assertEquals("4", s.roundsLabel)
        assertEquals(0, s.total)
        assertFalse(s.hasOpen)
    }

    @Test
    fun `stance map wins over the counters when the two disagree`() {
        // Gerçek bir arıza sınıfı: `support/oppose` alanları bir yolda
        // güncellenmeden kalabiliyor. Harita "kim" sorusunun tek kaynağı.
        val line = CouncilLiveRegistry.ClaimLine(
            id = "C1",
            text = "iddia",
            support = 0,
            oppose = 0,
            uncertain = 0,
            verdict = "contested",
            stances = mapOf(
                "Ayşe" to Stance.SUPPORT.wire,
                "Bora" to Stance.OPPOSE.wire,
                "Cem" to Stance.OPPOSE.wire,
            ),
        )
        val s = CouncilDigest.ofLines(listOf(line), participants = 3, roundsUsed = 1, roundsPlanned = 1)
        assertEquals(1, s.open.first().support)
        assertEquals(2, s.open.first().oppose)
        assertEquals(0, s.open.first().uncertain)
    }

    @Test
    fun `empty stance map falls back to the counters`() {
        val line = CouncilLiveRegistry.ClaimLine(
            id = "C2", text = "x", support = 4, oppose = 1, uncertain = 0, verdict = "decided",
        )
        val s = CouncilDigest.ofLines(listOf(line), participants = 5, roundsUsed = 2, roundsPlanned = 2)
        assertEquals(1, s.decided)
        assertFalse(s.hasOpen)
    }
}
