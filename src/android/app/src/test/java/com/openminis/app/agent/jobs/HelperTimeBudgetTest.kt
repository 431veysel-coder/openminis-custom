package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37099b] Alt ajanin duvar saati butcesi.
 *
 * Olcum (2026-11-23): uc paralel denetci, varsayilan 10 dakika ile 11m30s'de
 * kesildi. Hepsi 13-28 turdaydi, yani tur siniri uzaktaydi; run'i bitiren sey
 * saatti. Cocuk saatin varligini bilmiyordu: istemde yalniz tur sayisi vardi.
 *
 * Bu testler metnin ne soyledigini civiliyor. Davranisin kendisi
 * ChatViewModel'in dongusunde, cihazda.
 */
class HelperTimeBudgetTest {

    private fun cfg(minutes: Int) = HelperConfig(
        parentSessionId = "p",
        parentToolUseId = "t",
        jobId = "j",
        maxTurns = 40,
        title = "Survey",
        tierUsed = HelperModelTier.PRIMARY,
        minutes = minutes,
    )

    @Test
    fun `istem saat butcesini sayiyla soyler`() {
        val section = HelperRunner.identitySection(cfg(10))
        assertTrue(
            "child must know its wall-clock budget, not only its round cap",
            section.contains("10 minutes of wall-clock time"),
        )
        assertTrue(
            "either limit ends the run on its own",
            section.contains("either limit ends the run"),
        )
    }

    @Test
    fun `sure uyarisi kalan dakikayi ve yazma emrini tasir`() {
        val note = HelperRunner.timeBudgetWarning(2)
        assertTrue(note.contains("2 minutes"))
        assertTrue(note.contains("Write the deliverable NOW"))
        assertTrue(note.contains("do not end on a tool call"))
    }

    @Test
    fun `sifir ve bir dakika metni bozulmuyor`() {
        val zero = HelperRunner.timeBudgetWarning(0)
        val one = HelperRunner.timeBudgetWarning(1)
        assertTrue(zero.startsWith("[Time check:"))
        assertTrue(zero.contains("about no minute"))
        assertTrue(one.contains("about 1 minute left"))
    }

    @Test
    fun `varsayilan butce degismedi`() {
        assertEquals(10, HelperRunner.DEFAULT_MINUTES)
        assertEquals(60, HelperRunner.MAX_MINUTES)
        assertEquals(3, HelperRunner.TIME_WARNING_LEAD_MINUTES)
    }
}
