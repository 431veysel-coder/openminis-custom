package com.openminis.app.agent.team

import android.content.Context
import com.openminis.app.data.team.Team
import com.openminis.app.data.team.TeamMode
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [MINIS-OZEL 11.5 · 37037] What a finished run leaves behind.
 *
 * ## Why files and not Room
 *
 * A run record is written once, read whole, and never queried by field. Room
 * would mean a schema bump and a migration that can fail on an existing install,
 * for data that is read by one screen. `TeamStore` set this precedent for team
 * definitions and it holds here for the same reason: a corrupt file is skipped,
 * not thrown, so one bad run can never make the run list unusable.
 *
 * ## What is stored, and what is not
 *
 * Stored: the header (team, task, verdict, stop reason, counters, budget reason),
 * the final report, the claim matrix, and the dissent. NOT the full transcripts —
 * those live in each member's own child session and are reachable from the run's
 * screen; copying them here would duplicate megabytes per run for data the app
 * can already show.
 *
 * ## The rule this file enforces
 *
 * **A run that did not verify is recorded as unverified.** `verified` comes from
 * the audit, and there is no path in this class that writes `true` on its own.
 * A stored record is what the user reads later; a record that flatters the run is
 * worse than no record.
 */
object TeamRunStore {

    private const val TAG = "TeamRunStore"
    private const val DIR = "team-runs"
    private const val HEADER = "run.json"
    private const val REPORT = "report.md"

    fun directory(context: Context): File = File(context.filesDir, DIR)
    private fun runDir(context: Context, runId: String): File = File(directory(context), runId)

    fun record(
        context: Context,
        runId: String,
        team: Team,
        outcome: CouncilOutcome,
        policy: CouncilPolicy.Preset,
    ): File {
        val dir = runDir(context, runId)
        dir.mkdirs()
        val header = JSONObject().apply {
            put("runId", runId)
            put("teamId", team.id)
            put("teamName", team.name)
            put("mode", team.mode.wire)
            put("createdAt", System.currentTimeMillis())
            put("rounds", outcome.rounds)
            put("stoppedBy", outcome.stoppedBy.name)
            put("verified", outcome.verified)
            put("tokensUsed", outcome.tokensUsed)
            put("tokensEstimated", true)
            put("policy", JSONObject().apply {
                put("label", policy.label)
                put("reason", policy.reason)
                put("waveSize", policy.budget.waveSize)
                put("maxRounds", policy.budget.maxRounds)
            })
            put("members", JSONArray(team.members.map { it.name }))
            put("consensus", JSONObject().apply {
                put("unanimous", outcome.consensus.unanimous.size)
                put("decided", outcome.consensus.decided.size)
                put("contested", outcome.consensus.contested.size)
                put("unresolved", outcome.consensus.unresolved.size)
            })
            put("claims", JSONArray(outcome.matrix.claims.map { c ->
                JSONObject().apply {
                    put("id", c.id)
                    put("text", c.text)
                    put("support", c.support)
                    put("oppose", c.oppose)
                    put("uncertain", c.uncertain)
                }
            }))
            put("dissent", JSONArray(outcome.dissent))
            outcome.audit?.let { a ->
                put("audit", JSONObject().apply {
                    put("passed", a.passed)
                    put("problems", JSONArray(a.problems))
                })
            }
        }
        // Temp-then-rename, the same atomicity TeamStore uses: a crash mid-write
        // must not leave a half-parsed run.json that the list then skips forever.
        val tmp = File(dir, "$HEADER.tmp")
        tmp.writeText(header.toString(2))
        val target = File(dir, HEADER)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        File(dir, REPORT).writeText(outcome.answer)
        AppLogger.info(TAG, "recorded $runId verified=${outcome.verified} rounds=${outcome.rounds}")
        return dir
    }

    /** Newest first. A corrupt header is skipped with a log line, never thrown. */
    fun list(context: Context): List<Header> {
        val dir = directory(context)
        val dirs = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val file = File(d, HEADER)
            if (!file.exists()) return@mapNotNull null
            runCatching { parse(file.readText()) }
                .onFailure { AppLogger.info(TAG, "skip unreadable run ${d.name}: ${it.message}") }
                .getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun report(context: Context, runId: String): String? =
        runCatching { File(runDir(context, runId), REPORT).readText() }.getOrNull()

    /**
     * [37061] Delete a run: its record AND the members' workspace directories.
     *
     * The two are tied together on purpose. A member's files live under
     * `minis-sessions/sp_<runId8>_<slug>/`, which the orphan sweeper
     * (`OrphanedSessionFiles`) deliberately ignores — it only touches folders
     * whose name is a UUID, so a `sp_` folder is never "orphaned" in its eyes and
     * would otherwise accumulate one workspace per run forever.
     */
    fun delete(context: Context, runId: String) {
        runDir(context, runId).deleteRecursively()
        memberSpaceDirs(context, runId).forEach { it.deleteRecursively() }
    }

    /**
     * The run part of a member workspace directory name, or null when the name is
     * not one of ours.
     *
     * Pure and separate from [sweepOrphanSpaces] because the naming rule is the
     * only thing standing between "clean up my own leftovers" and "delete a chat's
     * files": `sp_<8 hex>_<slug>`. A name that does not match exactly (a session
     * id, a UUID folder, a user's own directory) must come back null and be left
     * alone. Tested in `TeamRunStoreTest`.
     */
    fun runPartOfSpaceDir(name: String): String? {
        if (!name.startsWith("sp_")) return null
        val rest = name.removePrefix("sp_")
        val runPart = rest.substringBefore('_')
        if (runPart.length != RUN_ID_SHORT) return null
        if (!runPart.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        // A slug must follow, otherwise "sp_deadbeef" (no separator) is a name
        // somebody else chose.
        if (!rest.contains('_')) return null
        return runPart
    }

    /** Hex characters in the short run id used inside a workspace directory. */
    const val RUN_ID_SHORT = 8

    /** Host directories a run's members wrote into. Empty is normal. */
    fun memberSpaceDirs(context: Context, runId: String): List<File> {
        val prefix = "sp_${runId.take(8)}_"
        val root = File(context.filesDir, "minis-sessions")
        return root.listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }?.toList().orEmpty()
    }

    /** Bytes a run's member workspaces occupy, for the run list's footer. */
    fun memberSpaceBytes(context: Context, runId: String): Long =
        memberSpaceDirs(context, runId).sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /**
     * [37061] Remove member workspaces whose run record is gone.
     *
     * Called once before a run starts, which is the cheapest place: runs are rare
     * compared to turns, and sweeping at start means a crash during a run leaves
     * its workspace for the NEXT run to clean rather than for a timer nobody
     * owns. Returns how many directories were removed.
     */
    fun sweepOrphanSpaces(context: Context): Int {
        val root = File(context.filesDir, "minis-sessions")
        val known = list(context).map { it.runId.take(8) }.toSet()
        var removed = 0
        root.listFiles { f -> f.isDirectory && f.name.startsWith("sp_") }?.forEach { dir ->
            val runPart = runPartOfSpaceDir(dir.name) ?: return@forEach
            if (runPart !in known && dir.deleteRecursively()) removed++
        }
        if (removed > 0) AppLogger.info(TAG, "swept $removed orphaned member workspace(s)")
        return removed
    }

    data class Header(
        val runId: String,
        val teamId: String,
        val teamName: String,
        val mode: String,
        val createdAt: Long,
        val rounds: Int,
        val stoppedBy: String,
        val verified: Boolean,
        val tokensUsed: Int,
        val members: List<String>,
        val consensus: Map<String, Int>,
        val dissent: List<String>,
        val policyLabel: String,
        val policyReason: String,
    ) {
        /** One line for a list row; never says "done" for an unverified run. */
        fun statusLine(): String = buildString {
            append(if (verified) "doğrulandı" else "DOĞRULANMADI")
            append(" · $rounds tur · ")
            append(stoppedBy.lowercase())
            append(" · ${tokensUsed} token")
        }
    }

    private fun parse(text: String): Header {
        val o = JSONObject(text)
        val consensus = o.optJSONObject("consensus")
        val members = o.optJSONArray("members")
        val dissent = o.optJSONArray("dissent")
        val policy = o.optJSONObject("policy")
        return Header(
            runId = o.getString("runId"),
            teamId = o.optString("teamId"),
            teamName = o.optString("teamName"),
            mode = o.optString("mode", TeamMode.COUNCIL.wire),
            createdAt = o.optLong("createdAt"),
            rounds = o.optInt("rounds"),
            stoppedBy = o.optString("stoppedBy"),
            verified = o.optBoolean("verified", false),
            tokensUsed = o.optInt("tokensUsed"),
            members = if (members == null) emptyList() else
                (0 until members.length()).map { members.optString(it) },
            consensus = if (consensus == null) emptyMap() else
                consensus.keys().asSequence().associateWith { consensus.optInt(it) },
            dissent = if (dissent == null) emptyList() else
                (0 until dissent.length()).map { dissent.optString(it) },
            policyLabel = policy?.optString("label").orEmpty(),
            policyReason = policy?.optString("reason").orEmpty(),
        )
    }
}
