package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [37099g · §4.1] Alt ajan kartında "7/10 dk".
 *
 * Neden test: bütçe bilinmiyorken uydurulmuş bir sayı göstermek, kullanıcıya
 * yanlış bir "kaldı" hissi verir. 0 = bilinmiyor kuralı burada çivili.
 */
class HelperBudgetClockTest {

    @Test
    fun `butce bilinmiyorsa satir cizilmez`() {
        assertNull(AgentJobRegistry.budgetClock(120_000, 0))
        assertNull(AgentJobRegistry.budgetClock(null, 0))
    }

    @Test
    fun `dakika ve butce birlikte yazilir`() {
        assertEquals("7/10 dk", AgentJobRegistry.budgetClock(7 * 60_000L, 10))
        assertEquals("0/10 dk", AgentJobRegistry.budgetClock(30_000, 10))
    }

    @Test
    fun `butce asilirsa gercek yazilir`() {
        // Çocuk uyarıdan sonra da yazabiliyor; "12/10" bir hata değil, gerçek.
        assertEquals("12/10 dk", AgentJobRegistry.budgetClock(12 * 60_000L, 10))
    }
}
