package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [37099k · §4.4] Kuyruk özeti — cihazsız koşar.
 *
 * Neden test: bu satır "hiç iş yok" durumunda **çizilmemeli**. Her sohbette
 * "0 çalışıyor" yazan bir şerit bilgi değil gürültüdür; ve sıfır olan kalem
 * satıra hiç girmemeli.
 */
class QueueSummaryTest {

    @Test
    fun `is yoksa null`() {
        assertNull(AgentJobRegistry.queueSummaryOf(0, 0, 0))
    }

    @Test
    fun `yalniz sifir olmayan kalemler yazilir`() {
        assertEquals("2 çalışıyor", AgentJobRegistry.queueSummaryOf(2, 0, 0))
        assertEquals("3 sırada", AgentJobRegistry.queueSummaryOf(0, 0, 3))
        assertEquals("1 çalışıyor, 2 sırada", AgentJobRegistry.queueSummaryOf(1, 0, 2))
    }

    @Test
    fun `uc kalem de doluysa sira korunur`() {
        assertEquals(
            "2 çalışıyor, 1 başlıyor, 3 sırada",
            AgentJobRegistry.queueSummaryOf(2, 1, 3),
        )
    }
}
