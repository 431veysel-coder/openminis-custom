package com.openminis.app.ozel

import com.openminis.app.agent.AgentKind
import com.openminis.app.agent.team.MemberToolBelt
import com.openminis.app.data.team.TeamMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 11.5 · 37060] The member belt, pinned.
 *
 * These are the tests that make `TeamMember.tools` a promise instead of a field.
 * Each one names a specific way the belt could be wrong in production, because a
 * belt that is merely "about right" is worse than none: the user configures a
 * reviewer that must not write, the reviewer writes, and nothing anywhere says
 * why.
 */
class MemberToolBeltTest {

    // ── The kind boundary ──────────────────────────────────────────────────

    @Test
    fun `only a member gets a filtered belt`() {
        // TOP and HELPER keep their own belts. Returning a member set for either
        // would strip a normal chat of its tools, which is the worst possible
        // regression from a feature nobody asked for.
        assertEquals(emptySet<String>(), MemberToolBelt.resolve(AgentKind.TOP, "engineer", emptyList()))
        assertEquals(emptySet<String>(), MemberToolBelt.resolve(AgentKind.HELPER, "engineer", emptyList()))
    }

    @Test
    fun `the old two-value mapping cannot produce a member`() {
        assertTrue(AgentKind.of(true) == AgentKind.HELPER)
        assertTrue(AgentKind.of(false) == AgentKind.TOP)
    }

    @Test
    fun `a member never gets team_run whatever it asks for`() {
        val asked = MemberToolBelt.resolve(AgentKind.MEMBER, "engineer", listOf("team_run", "shell_execute"))
        assertFalse("a member must not start a nested team", "team_run" in asked)
        assertTrue("shell_execute was explicitly asked for and is allowed", "shell_execute" in asked)
    }

    @Test
    fun `a member cannot escape the allow-list by naming an unknown tool`() {
        val asked = MemberToolBelt.resolve(AgentKind.MEMBER, "", listOf("rm_rf", "shell_execute"))
        assertEquals(setOf("shell_execute"), asked)
    }

    // ── Role presets ───────────────────────────────────────────────────────

    @Test
    fun `a researcher cannot run a shell`() {
        val belt = MemberToolBelt.resolve(AgentKind.MEMBER, "researcher", emptyList())
        // The measured failure: a research member with a shell spends its round
        // installing things instead of answering.
        assertFalse("shell_execute" in belt)
        assertTrue("browser_use" in belt)
        assertTrue("memory_write" in belt)
    }

    @Test
    fun `a reviewer reads and hands work back but does not write`() {
        val belt = MemberToolBelt.resolve(AgentKind.MEMBER, "reviewer", emptyList())
        assertFalse("file_write" in belt)
        assertFalse("file_edit" in belt)
        assertFalse("shell_execute" in belt)
        assertTrue("file_read" in belt)
        assertTrue("assign_task" in belt)
        assertTrue("ask_teammate" in belt)
    }

    @Test
    fun `a critic can argue but not execute or edit`() {
        val belt = MemberToolBelt.resolve(AgentKind.MEMBER, "critic", emptyList())
        assertFalse("shell_execute" in belt)
        assertFalse("file_write" in belt)
        // No memory_write: a critic's job is judgement, and a critic writing
        // long-term notes about the team's memory is how a group memory becomes
        // one vocal member's opinions.
        assertFalse("memory_write" in belt)
        assertTrue("memory_get" in belt)
        assertTrue("ask_teammate" in belt)
    }

    @Test
    fun `an engineer gets the run-and-build belt`() {
        val belt = MemberToolBelt.resolve(AgentKind.MEMBER, "engineer", emptyList())
        assertTrue("shell_execute" in belt)
        assertTrue("file_write" in belt)
        assertTrue("subagent_task" in belt)
    }

    @Test
    fun `an unknown role falls back to the generalist belt`() {
        val belt = MemberToolBelt.resolve(AgentKind.MEMBER, "fotoğrafçı", emptyList())
        assertTrue("shell_execute" in belt)
        assertTrue("browser_use" in belt)
        assertFalse("team_run" in belt)
    }

    @Test
    fun `an empty role is the generalist belt, not an empty one`() {
        val belt = MemberToolBelt.resolve(AgentKind.MEMBER, "", emptyList())
        // "Nobody said" must never mean "no tools": that member would answer
        // every round with "I have no tools" and look like a broken model.
        assertTrue(belt.size >= 10)
    }

    // ── Role strings in both languages ─────────────────────────────────────

    @Test
    fun `a Turkish role resolves to the same preset as its English name`() {
        assertEquals(
            MemberToolBelt.rolePreset("researcher"),
            MemberToolBelt.rolePreset("araştırmacı"),
        )
        assertEquals(
            MemberToolBelt.rolePreset("engineer"),
            MemberToolBelt.rolePreset("yazılımcı"),
        )
        // A user writing the team in Turkish must not silently get the
        // generalist belt, which is what happens when only English is matched.
        assertNull(MemberToolBelt.rolePreset("moderatör"))
    }

    @Test
    fun `a role with a trailing description still resolves`() {
        // The field is free text and the hint asks for a sentence, so the first
        // word is the only reliable part.
        assertEquals(
            MemberToolBelt.rolePreset("researcher"),
            MemberToolBelt.rolePreset("Researcher: reads the sources"),
        )
        assertEquals(
            MemberToolBelt.rolePreset("reviewer"),
            MemberToolBelt.rolePreset("reviewer — checks the diff"),
        )
    }

    // ── Declared list wins, deny still wins over it ─────────────────────────

    @Test
    fun `a declared list replaces the preset entirely`() {
        val declared = listOf("browser_use", "file_read")
        val belt = MemberToolBelt.resolve(AgentKind.MEMBER, "engineer", declared)
        // Engineer's preset includes shell_execute; the explicit list does not,
        // and an explicit list means exactly what it says.
        assertEquals(setOf("browser_use", "file_read"), belt)
    }

    @Test
    fun `the member ceiling beats a declared list`() {
        val belt = MemberToolBelt.resolve(AgentKind.MEMBER, "", listOf("team_run", "shell_execute"))
        assertFalse("team_run" in belt)
        assertTrue("shell_execute" in belt)
    }

    // ── Refusals say why ───────────────────────────────────────────────────

    @Test
    fun `a refused tool names the reason`() {
        val reserved = MemberToolBelt.refusal("team_run", "engineer", emptyList())
        assertTrue(reserved.contains("team_run"))
        assertTrue("the message must say where the rule comes from", reserved.contains("reserved"))

        val roleBased = MemberToolBelt.refusal("shell_execute", "researcher", emptyList())
        assertTrue("a hint is not a reason", roleBased.contains("researcher"))

        val declared = MemberToolBelt.refusal("shell_execute", "engineer", listOf("file_read"))
        assertTrue(declared.contains("explicit tool list"))
    }

    // ── Team mode ──────────────────────────────────────────────────────────

    @Test
    fun `an unknown or missing mode is handoff, never council`() {
        // The safe default matters: a parse failure that produced COUNCIL would
        // spend ten times the tokens on a team the user configured for artefacts.
        assertEquals(TeamMode.HANDOFF, TeamMode.parse(null))
        assertEquals(TeamMode.HANDOFF, TeamMode.parse(""))
        assertEquals(TeamMode.HANDOFF, TeamMode.parse("someday"))
        assertEquals(TeamMode.COUNCIL, TeamMode.parse("COUNCIL"))
        assertEquals(TeamMode.FANOUT, TeamMode.parse(" fanout "))
    }

    @Test
    fun `every mode has a distinct wire name`() {
        val wires = TeamMode.entries.map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
    }
}
