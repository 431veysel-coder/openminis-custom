package com.openminis.app.ozel

import com.openminis.app.agent.team.RunSubAgentBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 11.5 · 37062] The run-level sub agent budget.
 *
 * The registry's own cap cannot bound a council — it counts per PARENT, and every
 * member is a parent — so this arithmetic is the only thing standing between "each
 * member may delegate" and fifteen concurrent agent loops on a phone. That makes
 * it worth testing on its own rather than trusting the wiring.
 */
class RunSubAgentBudgetTest {

    @Test
    fun `slots bound concurrency and the launch ceiling bounds the run`() {
        val budget = RunSubAgentBudget(slots = 3, maxLaunches = 5)
        assertTrue(budget.canStart(active = 2))
        assertFalse("three already running is the limit", budget.canStart(active = 3))
        assertTrue(budget.canLaunch(launched = 4))
        assertFalse("five starts is the run's ceiling", budget.canLaunch(launched = 5))
        // Both gates at once: room for one more AND a launch left.
        assertTrue(budget.admit(active = 2, launched = 4))
        assertFalse(budget.admit(active = 3, launched = 0))
        assertFalse(budget.admit(active = 0, launched = 5))
    }

    @Test
    fun `the launch ceiling is a total, not a concurrent limit`() {
        // A run that starts sub agents one at a time across many rounds is
        // exactly what the budget is meant to allow; only the total is capped.
        val budget = RunSubAgentBudget(slots = 1, maxLaunches = 12)
        assertTrue(budget.admit(active = 0, launched = 11))
        assertFalse(budget.admit(active = 0, launched = 12))
    }

    @Test
    fun `the refusal text names the counting rule and offers the task path`() {
        val budget = RunSubAgentBudget(slots = 2, maxLaunches = 12)
        val busy = budget.refusal(active = 2, launched = 1)
        assertTrue("must state the concurrency limit", busy.contains("2"))
        assertTrue("must offer the alternative", busy.contains("assign_task"))

        val spent = budget.refusal(active = 0, launched = 12)
        assertTrue("must state the launch ceiling", spent.contains("12"))
        assertTrue(spent.contains("assign_task"))
        // The two refusals must be distinguishable: they need different fixes.
        assertFalse(busy == spent)
    }

    @Test
    fun `device classes give a shrinking budget, never an unbounded one`() {
        assertEquals(2, RunSubAgentBudget.forDevice(isLowRamDevice = true, totalRamMb = 3000).slots)
        assertEquals(2, RunSubAgentBudget.forDevice(isLowRamDevice = false, totalRamMb = 3000).slots)
        assertEquals(3, RunSubAgentBudget.forDevice(isLowRamDevice = false, totalRamMb = 6000).slots)
        assertEquals(4, RunSubAgentBudget.forDevice(isLowRamDevice = false, totalRamMb = 12000).slots)
        // Every preset keeps the run ceiling, so no device class is unlimited.
        listOf(
            RunSubAgentBudget.forDevice(true, 2000),
            RunSubAgentBudget.forDevice(false, 6000),
            RunSubAgentBudget.forDevice(false, 16000),
        ).forEach { assertEquals(RunSubAgentBudget.MAX_LAUNCHES, it.maxLaunches) }
    }

    @Test
    fun `a council of five cannot exceed the run budget however hard it fans out`() {
        // The arithmetic that motivated the whole class: five members each at the
        // registry's per-parent cap of three would be fifteen loops. The run
        // budget caps the total at `slots`.
        val slots = 3
        val budget = RunSubAgentBudget(slots = slots)
        var active = 0
        var launched = 0
        var admitted = 0
        repeat(5) { // five members, each asking three times
            repeat(3) {
                if (budget.admit(active, launched)) {
                    admitted++
                    active++
                    launched++
                }
            }
        }
        assertEquals("concurrency cap, not member count, decides", slots, admitted)
        assertTrue(admitted < 15)
    }

    @Test
    fun `the disabled budget admits nothing`() {
        val disabled = RunSubAgentBudget.DISABLED
        assertFalse(disabled.admit(active = 0, launched = 0))
        assertTrue(disabled.refusal(0, 0).isNotBlank())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a budget with no slots is a programming error`() {
        // A zero-slot budget would make every delegation fail silently while the
        // tool was still advertised; failing construction makes that loud.
        RunSubAgentBudget(slots = 0)
    }
}
