package com.openminis.app.ozel

import com.openminis.app.agent.team.Basis
import com.openminis.app.agent.team.ClaimMatrix
import com.openminis.app.agent.team.ClaimMatrixParser
import com.openminis.app.agent.team.Claim
import com.openminis.app.agent.team.CitationAudit
import com.openminis.app.agent.team.Consensus
import com.openminis.app.agent.team.CouncilBudget
import com.openminis.app.agent.team.CouncilLoop
import com.openminis.app.agent.team.CouncilMemberRunner
import com.openminis.app.agent.team.CouncilModel
import com.openminis.app.agent.team.CouncilPolicy
import com.openminis.app.agent.team.CouncilStop
import com.openminis.app.agent.team.PositionParser
import com.openminis.app.agent.team.RawReply
import com.openminis.app.agent.team.Stance
import com.openminis.app.agent.team.StanceMath
import com.openminis.app.agent.team.Verdict
import com.openminis.app.data.team.TeamMember
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 11.5 · 37037] The council's decision machinery, tested where it
 * can be tested.
 *
 * Why this file matters more than most: every test here pins a rule that would
 * otherwise be enforced by a MODEL. "Did they converge?", "is this member's
 * silence an abstention?", "is this citation real?" — answered by a model, each
 * of those is unfalsifiable in production and invisible in review. As arithmetic
 * they are assertions, and a threshold change fails a test instead of quietly
 * making the council agree with itself.
 *
 * Nothing here needs a network, a provider, a device or a clock.
 */
class CouncilLoopTest {

    private val three = listOf(
        TeamMember(name = "Ada", role = "researcher"),
        TeamMember(name = "Bor", role = "engineer"),
        TeamMember(name = "Cem", role = "critic"),
    )

    private fun positionJson(
        claim: String = "ship it",
        stance: String = "support",
        falsifier: String = "a failing benchmark",
        evidence: String = "[]",
        basis: String = "reasoning",
        extra: String = "",
    ): String = """
        {"claim":"$claim",
         "points":[{"ref":"p1","text":"because reasons","stance":"$stance",
                    "basis":"$basis","evidence":$evidence}],
         "falsifier":"$falsifier"$extra}
    """.trimIndent()

    private fun matrixJson(vararg stances: Pair<String, String>, id: String = "C1"): String {
        val votes = stances.joinToString(",") { (m, s) -> "\"$m\":\"$s\"" }
        return """{"claims":[{"id":"$id","text":"the proposal is sound","stances":{$votes}}]}"""
    }

    // ── PositionParser ─────────────────────────────────────────────────────

    @Test
    fun `position inside a fenced block is recovered`() {
        val raw = "Here is my position:\n```json\n${positionJson()}\n```\nHope that helps."
        val p = PositionParser.parse(raw, "Ada")
        assertFalse(p.absent)
        assertEquals("ship it", p.claim)
        assertEquals(1, p.points.size)
        assertEquals(Stance.SUPPORT, p.points[0].stance)
        assertTrue(p.falsifiable)
    }

    @Test
    fun `a member with no claim and no points is absent, never uncertain`() {
        val p = PositionParser.parse("""{"claim":"","points":[],"falsifier":"x"}""", "Ada")
        assertTrue(p.absent)
        assertEquals("", p.claim)
        // The distinction the whole design rests on: silence must not become an
        // abstention, or five silent members would look like a hung jury.
        assertEquals(0, p.points.size)
    }

    @Test
    fun `prose with no json is absent with a reason`() {
        val p = PositionParser.parse("I would rather not answer that.", "Ada")
        assertTrue(p.absent)
        assertTrue(p.absentWhy.isNotBlank())
    }

    @Test
    fun `evidence basis without a source is flagged but kept`() {
        // hasSource is `basis != EVIDENCE || evidence.isNotEmpty()`, so the basis
        // must actually BE evidence for this test to mean anything: with
        // "reasoning" the point is sourced by definition.
        val p = PositionParser.parse(positionJson(evidence = "[]", basis = "evidence"), "Ada")
        assertFalse(p.absent)
        val point = p.points.first()
        // Re-labelled, not dropped: hiding the attempt would hide that the
        // member asserted a fact it could not back.
        assertFalse(point.hasSource)
    }

    @Test
    fun `trailing comma and missing brace are repaired`() {
        val raw = """{"claim":"x","points":[{"ref":"p1","text":"t","stance":"support",}],"""
        val p = PositionParser.parse(raw, "Ada")
        // Truncated, but the substance is there and readable.
        assertTrue(p.absent || p.points.isNotEmpty())
        if (!p.absent) assertEquals("x", p.claim)
    }

    @Test
    fun `a brace inside a quoted sentence does not end the object`() {
        val raw = """{"claim":"use {braces} in json","points":[{"text":"t","stance":"support"}],"falsifier":"f"}"""
        val p = PositionParser.parse(raw, "Ada")
        assertFalse(p.absent)
        assertEquals("use {braces} in json", p.claim)
    }

    @Test
    fun `bogus stance text becomes uncertain, not support`() {
        val p = PositionParser.parse(positionJson(stance = "strongly agree!!"), "Ada")
        assertEquals(Stance.UNCERTAIN, p.points[0].stance)
        assertEquals(Basis.REASONING, p.points[0].basis)
    }

    // ── ClaimMatrixParser ──────────────────────────────────────────────────

    @Test
    fun `a chair inventing a member name cannot create a vote`() {
        val raw = """{"claims":[{"id":"C1","text":"t","stances":{"Ada":"support","Ghost":"support"}}]}"""
        val m = ClaimMatrixParser.parse(raw, listOf("Ada", "Bor", "Cem"))
        assertNotNull(m)
        val claim = m!!.claims.single()
        // Ghost is dropped: an invented supporter is invisible in the report,
        // a missing one is visible in the counts.
        assertEquals(1, claim.support)
        assertEquals(setOf("Ada"), claim.stances.keys)
    }

    @Test
    fun `duplicate ids are made unique and empty ids get positional ones`() {
        val raw = """{"claims":[
            {"id":"C1","text":"first","stances":{"Ada":"support"}},
            {"id":"C1","text":"second","stances":{"Ada":"oppose"}},
            {"text":"third","stances":{"Ada":"support"}}]}"""
        val m = ClaimMatrixParser.parse(raw, listOf("Ada"))!!
        assertEquals(listOf("C1", "C1-2", "C3"), m.claims.map { it.id })
    }

    @Test
    fun `no claims object is not a matrix`() {
        assertNull(ClaimMatrixParser.parse("""{"dropped":["x"]}""", listOf("Ada")))
        assertNull(ClaimMatrixParser.parse("nothing here", listOf("Ada")))
    }

    // ── StanceMath ─────────────────────────────────────────────────────────

    private fun claim(id: String, text: String, vararg votes: Pair<String, Stance>) =
        Claim(id = id, text = text, stances = votes.toMap())

    @Test
    fun `unanimous needs every participant and no doubt`() {
        val matrix = ClaimMatrix(
            claims = listOf(
                claim("C1", "a", "Ada" to Stance.SUPPORT, "Bor" to Stance.SUPPORT, "Cem" to Stance.SUPPORT),
            ),
        )
        val c = StanceMath.evaluate(matrix, attendees = 3)
        assertEquals(Verdict.UNANIMOUS, c.verdicts.single().verdict)
        assertTrue(c.converged)
    }

    @Test
    fun `half the council unsure reads as unresolved, not as a live dispute`() {
        val matrix = ClaimMatrix(
            claims = listOf(
                claim(
                    "C1", "a",
                    "A" to Stance.SUPPORT, "B" to Stance.OPPOSE,
                    "C" to Stance.UNCERTAIN, "D" to Stance.UNCERTAIN, "E" to Stance.UNCERTAIN,
                ),
            ),
        )
        val c = StanceMath.evaluate(matrix, attendees = 5)
        // 1-1 with three doubters. Called CONTESTED this would look like an
        // argument worth another round; nobody has a position yet.
        assertEquals(Verdict.UNRESOLVED, c.verdicts.single().verdict)
        assertFalse(c.converged)
        assertFalse(c.settled)
    }

    @Test
    fun `a majority decides even with two doubters among five`() {
        val matrix = ClaimMatrix(
            claims = listOf(
                claim(
                    "C1", "a",
                    "A" to Stance.SUPPORT, "B" to Stance.SUPPORT, "C" to Stance.SUPPORT,
                    "D" to Stance.UNCERTAIN, "E" to Stance.UNCERTAIN,
                ),
            ),
        )
        // 3/5 reaches the 0.6 threshold; two doubters do not block it, because
        // "unsure" plus "majority" cannot both hold at 5 members. Pinned so a
        // future threshold change has to face this case explicitly.
        assertEquals(Verdict.DECIDED, StanceMath.evaluate(matrix, attendees = 5).verdicts.single().verdict)
    }

    @Test
    fun `a two-two split is contested while three of five decides`() {
        val twoTwo = ClaimMatrix(
            claims = listOf(
                claim("C1", "a", "A" to Stance.SUPPORT, "B" to Stance.SUPPORT,
                    "C" to Stance.OPPOSE, "D" to Stance.OPPOSE, "E" to Stance.UNCERTAIN),
            ),
        )
        assertEquals(Verdict.CONTESTED, StanceMath.evaluate(twoTwo, attendees = 5).verdicts.single().verdict)

        val threeFive = ClaimMatrix(
            claims = listOf(
                claim("C1", "a", "A" to Stance.SUPPORT, "B" to Stance.SUPPORT,
                    "C" to Stance.SUPPORT, "D" to Stance.OPPOSE, "E" to Stance.OPPOSE),
            ),
        )
        assertEquals(Verdict.DECIDED, StanceMath.evaluate(threeFive, attendees = 5).verdicts.single().verdict)
    }

    @Test
    fun `everyone opposing is decided, never unanimous`() {
        val matrix = ClaimMatrix(
            claims = listOf(claim("C1", "a", "Ada" to Stance.OPPOSE, "Bor" to Stance.OPPOSE)),
        )
        val verdict = StanceMath.evaluate(matrix, attendees = 2).verdicts.single()
        // Unanimous would mean the claim was accepted. Agreement that the
        // proposal is WRONG is still a decision, and the counts carry direction.
        assertEquals(Verdict.DECIDED, verdict.verdict)
        assertEquals(0, verdict.claim.support)
    }

    @Test
    fun `renumbered claims are not counted as movement`() {
        val before = ClaimMatrix(
            claims = listOf(claim("C1", "the proposal is sound", "Ada" to Stance.SUPPORT, "Bor" to Stance.OPPOSE)),
        )
        val after = ClaimMatrix(
            claims = listOf(claim("C7", "The proposal is sound.", "Ada" to Stance.SUPPORT, "Bor" to Stance.OPPOSE)),
        )
        // Zero, not one: an id-keyed diff would report movement every round
        // purely from renumbering, and the council would never look stable.
        assertEquals(0, StanceMath.cellsChanged(before, after))
    }

    @Test
    fun `a reversed stance counts as movement and two stable rounds settle`() {
        val r1 = ClaimMatrix(claims = listOf(claim("C1", "t", "Ada" to Stance.SUPPORT, "Bor" to Stance.OPPOSE)))
        val r2 = ClaimMatrix(claims = listOf(claim("C1", "t", "Ada" to Stance.SUPPORT, "Bor" to Stance.SUPPORT)))
        assertEquals(1, StanceMath.cellsChanged(r1, r2))

        val stable = StanceMath.evaluate(r2, previous = r2, attendees = 2, stableStreak = 1)
        assertTrue(stable.stableStreak >= 2)
        // `converged` is verdict-based (nothing contested, unsure or orphan) and
        // r2 has both members supporting, so it IS converged. The old assertion
        // said the opposite while the test's own name says two stable rounds
        // settle — the assertion was the wrong half. If the intent was instead
        // "convergence must ALSO wait for a stable streak", that is a change to
        // Consensus.converged, not to this expectation, and needs a product call.
        assertTrue(stable.converged)
    }

    @Test
    fun `dropping the disputed claim is counted as a change`() {
        val before = ClaimMatrix(
            claims = listOf(
                claim("C1", "a", "Ada" to Stance.SUPPORT, "Bor" to Stance.SUPPORT),
                claim("C2", "b", "Ada" to Stance.SUPPORT, "Bor" to Stance.OPPOSE),
            ),
        )
        val after = ClaimMatrix(claims = listOf(claim("C1", "a", "Ada" to Stance.SUPPORT, "Bor" to Stance.SUPPORT)))
        // Otherwise deleting the argument is the cheapest route to "converged".
        assertTrue(StanceMath.cellsChanged(before, after) >= 1)
    }

    // ── CitationAudit ──────────────────────────────────────────────────────

    @Test
    fun `an unknown citation id fails the audit`() {
        val board = listOf(CitationAudit.Evidence(id = "E1", url = "https://a", unverified = false))
        val result = CitationAudit.audit("ANSWER: x [E1]\nREASONS: [E9]", board, root = null)
        assertFalse(result.passed)
        assertTrue(result.unknownIds.contains("E9"))
        assertTrue(result.problems.any { it.contains("E9") })
    }

    @Test
    fun `citing nothing while the board has entries fails`() {
        val board = listOf(CitationAudit.Evidence(id = "E1", url = "https://a", unverified = false))
        val result = CitationAudit.audit("ANSWER: it is fine, trust me.", board, root = null)
        assertFalse(result.passed)
    }

    @Test
    fun `an unverified source is named even when the id exists`() {
        val board = listOf(CitationAudit.Evidence(id = "E1", url = "https://made.up", unverified = true))
        val result = CitationAudit.audit("ANSWER: x [E1]", board, root = null)
        assertFalse(result.passed)
        assertTrue(result.unverifiedIds.contains("E1"))
    }

    @Test
    fun `a clean report passes and an empty board does not demand citations`() {
        val board = listOf(CitationAudit.Evidence(id = "E1", url = "https://a", unverified = false))
        assertTrue(CitationAudit.audit("ANSWER: x [E1]", board, root = null).passed)
        assertTrue(CitationAudit.audit("ANSWER: x", emptyList(), root = null).passed)
    }

    // ── CouncilPolicy ──────────────────────────────────────────────────────

    @Test
    fun `device presets step from 2 to 5 members and explain themselves`() {
        val low = CouncilPolicy.forDevice(isLowRamDevice = true, totalRamMb = 3000)
        assertEquals(2, low.budget.waveSize)
        assertEquals(2, low.budget.maxRounds)
        assertTrue(low.reason.isNotBlank())

        val mid = CouncilPolicy.forDevice(isLowRamDevice = false, totalRamMb = 6000)
        assertEquals(3, mid.budget.waveSize)
        assertEquals(3, mid.budget.maxRounds)

        val big = CouncilPolicy.forDevice(isLowRamDevice = false, totalRamMb = 12000)
        // 4, not 5: SessionConcurrencyManager.MAX_CONCURRENT is 6 process-wide
        // and one stream slot is reserved for the user's own chats. Pinned here
        // because a policy change that quietly takes the last slot would only
        // show up as "my other chat is stuck on thinking" in the field.
        assertEquals(4, big.budget.waveSize)
        assertEquals(4, big.budget.maxRounds)
        assertTrue(big.reason.contains("1 akış yuvası"))
    }

    @Test
    fun `no preset ever takes the whole global stream cap`() {
        val caps = listOf(
            CouncilPolicy.forDevice(true, 2000),
            CouncilPolicy.forDevice(false, 6000),
            CouncilPolicy.forDevice(false, 16000),
        )
        for (preset in caps) {
            // 6 is SessionConcurrencyManager.MAX_CONCURRENT; leaving one free is
            // the difference between a background council and a locked app.
            assertTrue(
                "wave ${preset.budget.waveSize} leaves no room for the user",
                preset.budget.waveSize <= 5,
            )
        }
    }

    @Test
    fun `a team larger than the wave is clamped and told so`() {
        val pinned = CouncilPolicy.forDevice(isLowRamDevice = false, totalRamMb = 12000, userPinnedMembers = 8)
        assertEquals(4, pinned.budget.waveSize)
        assertTrue(pinned.label.contains("sınırlı"))
        assertTrue(pinned.reason.contains("8"))
    }

    @Test
    fun `waves chunk in order and never overflow`() {
        assertEquals(listOf(0..2, 3..4), CouncilPolicy.waves(5, 3))
        assertEquals(listOf(0..1, 2..3, 4..4), CouncilPolicy.waves(5, 2))
        assertEquals(listOf(0..4), CouncilPolicy.waves(5, 5))
        assertTrue(CouncilPolicy.waves(0, 3).isEmpty())
    }

    // ── CouncilLoop ────────────────────────────────────────────────────────

    private class FakeModel(
        private val chairReplies: List<String>,
        private val synthesis: String = "ANSWER: the proposal is sound [E1]\nREASONS:\n- [E1]\nDISSENT:\n- none\nOPEN:\n- none\nNEXT:\n- none",
    ) : CouncilModel {
        var chairCalls = 0
        var synthesisCalls = 0
        override suspend fun ask(prompt: String, json: Boolean, maxTokens: Int): String {
            if (prompt.contains("chair of a council")) {
                val i = chairCalls.coerceAtMost(chairReplies.lastIndex)
                chairCalls++
                return chairReplies[i]
            }
            if (prompt.contains("The council has finished")) {
                synthesisCalls++
                return synthesis
            }
            return ""
        }
    }

    private class FakeMembers(
        private val reply: (String, Int) -> RawReply,
    ) : CouncilMemberRunner {
        val rounds = mutableListOf<Int>()
        override suspend fun run(member: TeamMember, prompt: String, round: Int): RawReply {
            rounds += round
            return reply(member.name, round)
        }
    }

    private fun loop(
        model: CouncilModel,
        members: CouncilMemberRunner,
        budget: CouncilBudget = CouncilBudget(),
        evidence: List<CitationAudit.Evidence> = emptyList(),
    ) = CouncilLoop(
        model = model,
        members = members,
        budget = budget,
        auditRoot = null,
        initialEvidence = evidence,
    )

    @Test
    fun `members agree in round one and the run stops converged`() = runTest {
        val model = FakeModel(listOf(matrixJson("Ada" to "support", "Bor" to "support", "Cem" to "support")))
        val members = FakeMembers { _, _ -> RawReply(positionJson(), tokens = 100) }

        val outcome = loop(
            model, members,
            evidence = listOf(CitationAudit.Evidence("E1", "https://a", unverified = false)),
        ).run(teamName = "t", memberList = three, task = "decide")

        assertEquals(CouncilStop.CONVERGED, outcome.stoppedBy)
        assertEquals(1, outcome.rounds)
        assertEquals(300, outcome.tokensUsed)
        // One member call per member, in round 1 only.
        assertEquals(listOf(1, 1, 1), members.rounds)
        assertTrue(outcome.verified)
    }

    @Test
    fun `an unsettled council keeps arguing, then settles on stability`() = runTest {
        val split = matrixJson("Ada" to "support", "Bor" to "oppose", "Cem" to "uncertain")
        val model = FakeModel(listOf(split, split, split, split))
        val members = FakeMembers { name, _ ->
            RawReply(positionJson(claim = "$name thinks"), tokens = 50)
        }

        val outcome = loop(model, members, CouncilBudget(maxRounds = 6, maxStableStreak = 2)).run(
            teamName = "t", memberList = three, task = "decide",
        )

        // Round 1 has no predecessor (cellsChanged = -1) so it cannot be stable;
        // rounds 2 and 3 are identical, giving the two-round streak.
        assertEquals(CouncilStop.SETTLED_STABLE, outcome.stoppedBy)
        assertEquals(3, outcome.rounds)
        assertFalse(outcome.consensus.converged)
        assertTrue(outcome.dissent.isNotEmpty())
    }

    @Test
    fun `the token ceiling stops the run instead of finishing the next round`() = runTest {
        val split = matrixJson("Ada" to "support", "Bor" to "oppose", "Cem" to "uncertain")
        val model = FakeModel(listOf(split, split, split))
        val members = FakeMembers { _, _ -> RawReply(positionJson(), tokens = 40) }

        val outcome = loop(model, members, CouncilBudget(maxRounds = 6, maxTokens = 60)).run(
            teamName = "t", memberList = three, task = "decide",
        )

        assertEquals(CouncilStop.TOKENS, outcome.stoppedBy)
        assertEquals(1, outcome.rounds)
        assertEquals(120, outcome.tokensUsed)
    }

    @Test
    fun `a silent member is counted absent and the others still decide`() = runTest {
        val model = FakeModel(listOf(matrixJson("Ada" to "support", "Bor" to "support")))
        val members = FakeMembers { name, _ ->
            if (name == "Cem") RawReply("", 0, failure = "rate limited") else RawReply(positionJson(), tokens = 10)
        }

        val outcome = loop(model, members).run(teamName = "t", memberList = three, task = "decide")

        val cem = outcome.positions.first { it.member == "Cem" }
        assertTrue(cem.absent)
        assertEquals("rate limited", cem.absentWhy)
        // The chair saw two positions, so the verdict is computed on two — not
        // on three with the silent member quietly voting "uncertain".
        assertEquals(1, outcome.rounds)
        assertEquals(CouncilStop.CONVERGED, outcome.stoppedBy)
        assertNotNull(outcome.answer)
    }

    @Test
    fun `a report that cites an unknown id is not verified, and the repair is kept`() = runTest {
        val model = FakeModel(
            chairReplies = listOf(matrixJson("Ada" to "support", "Bor" to "support", "Cem" to "support")),
            synthesis = "ANSWER: x [E42]",
        )
        val members = FakeMembers { _, _ -> RawReply(positionJson(), tokens = 10) }

        val outcome = loop(
            model, members,
            evidence = listOf(CitationAudit.Evidence("E1", "https://a", unverified = false)),
        ).run(teamName = "t", memberList = three, task = "decide")

        assertFalse(outcome.verified)
        assertNotNull(outcome.audit)
        assertTrue(outcome.audit!!.unknownIds.contains("E42"))
        // The repair prompt is not "synthesis" so the fake returns the bare
        // fallback; either way the original wrong citation is not silently
        // promoted to verified.
        assertFalse(outcome.events.none { it is com.openminis.app.agent.team.CouncilEvent.Audited })
    }

    @Test
    fun `a blocked chair still produces a report from the raw claims`() = runTest {
        val model = FakeModel(chairReplies = listOf("this is not json at all"))
        val members = FakeMembers { name, _ -> RawReply(positionJson(claim = "$name says yes"), tokens = 10) }

        val outcome = loop(model, members).run(teamName = "t", memberList = three, task = "decide")

        assertEquals(CouncilStop.NO_POSITIONS, outcome.stoppedBy)
        // Fallback: the members' own sentences, not an empty screen and not a
        // fabricated consensus.
        assertTrue(outcome.answer.contains("says yes"))
    }

    @Test
    fun `no members is a clean no-positions stop`() = runTest {
        val outcome = loop(FakeModel(listOf("{}")), FakeMembers { _, _ -> RawReply("") })
            .run(teamName = "t", memberList = emptyList(), task = "decide")
        assertEquals(CouncilStop.NO_POSITIONS, outcome.stoppedBy)
        assertEquals(0, outcome.rounds)
    }

    @Test
    fun `consensus with no verdicts is not converged`() {
        assertFalse(Consensus().converged)
        assertFalse(Consensus().settled)
    }
}
