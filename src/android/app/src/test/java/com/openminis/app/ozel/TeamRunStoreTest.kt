package com.openminis.app.ozel

import com.openminis.app.agent.team.TeamRunStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 11.5 · 37061] The member workspace naming rule.
 *
 * This is the only thing between "clean up my own leftovers" and "delete a
 * user's chat files", so it gets its own test file rather than a corner of
 * another one. Every case below is a name that exists on a real device under
 * `minis-sessions/`.
 */
class TeamRunStoreTest {

    @Test
    fun `a member workspace yields its run id`() {
        assertEquals("ab12cd34", TeamRunStore.runPartOfSpaceDir("sp_ab12cd34_ada"))
        assertEquals("AB12CD34", TeamRunStore.runPartOfSpaceDir("sp_AB12CD34_Ada_Lovelace"))
    }

    @Test
    fun `a chat session id is not a member workspace`() {
        // Session rows are UUIDs; a sweep must never see one as its own.
        assertNull(TeamRunStore.runPartOfSpaceDir("6f0c1e3a-2b4d-4c5e-8f90-123456789abc"))
        assertNull(TeamRunStore.runPartOfSpaceDir("__new__1712345678"))
    }

    @Test
    fun `a name with the right prefix but the wrong shape is left alone`() {
        // Not 8 hex characters.
        assertNull(TeamRunStore.runPartOfSpaceDir("sp_abc_ada"))
        assertNull(TeamRunStore.runPartOfSpaceDir("sp_ab12cd345_ada"))
        // Not hex at all — someone else's naming.
        assertNull(TeamRunStore.runPartOfSpaceDir("sp_zzzzzzzz_ada"))
        // No slug separator after the run part.
        assertNull(TeamRunStore.runPartOfSpaceDir("sp_ab12cd34"))
        // Not our prefix.
        assertNull(TeamRunStore.runPartOfSpaceDir("workspace"))
        assertNull(TeamRunStore.runPartOfSpaceDir("sp"))
    }

    @Test
    fun `the run id is the length the sweep and the runtime agree on`() {
        // CouncilRunner builds `sp_${runId.take(8)}_<slug>` and TeamRunStore
        // matches on the same 8 characters. A silent change to one of them would
        // leak every workspace forever, so the constant is pinned.
        assertEquals(8, TeamRunStore.RUN_ID_SHORT)
        val runId = "run_ab12cd34"
        val slug = "ada"
        val dir = "sp_${runId.take(TeamRunStore.RUN_ID_SHORT)}_$slug"
        assertEquals("ab12cd34", TeamRunStore.runPartOfSpaceDir(dir))
    }

    @Test
    fun `a verified run reads as verified and an unverified one does not pretend`() {
        val base = TeamRunStore.Header(
            runId = "run_1", teamId = "t", teamName = "Ekip", mode = "council",
            createdAt = 0L, rounds = 3, stoppedBy = "SETTLED_STABLE",
            verified = true, tokensUsed = 1234, members = listOf("Ada"),
            consensus = emptyMap(), dissent = emptyList(),
            policyLabel = "Dengeli", policyReason = "r",
        )
        assertTrue(base.statusLine().contains("doğrulandı"))
        val unverified = base.copy(verified = false)
        assertTrue(unverified.statusLine().contains("DOĞRULANMADI"))
        assertTrue(unverified.statusLine().contains("1234"))
    }
}
