package com.openminis.app.data.team

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [MINIS-OZEL 11.4] A team of agents the orchestrator can delegate to.
 *
 * Storage is one JSON file per team under `filesDir/teams/<id>.json`, NOT a
 * Room table: team definitions change rarely, are read whole, and never need a
 * query. Keeping them out of Room also keeps this first slice additive — no
 * schema version bump, no migration, nothing that can fail an upgrade in the
 * field.
 *
 * The fields are already the ones the run loop needs (S2): a name, the
 * orchestrator's model, and members that each carry their own model, role
 * prompt, tool allowlist and scope. An empty `tools` list means "inherit the
 * default belt" rather than "no tools" — denying every tool by accident is a
 * far worse failure than granting the default.
 */
data class TeamMember(
    val name: String,
    val role: String = "",
    val providerInstanceId: String? = null,
    val modelId: String? = null,
    /** Empty = inherit the session's tool belt. */
    val tools: List<String> = emptyList(),
    /** "open" | "read_only" | "workspace" — see AgentScope. Empty = inherit. */
    val scope: String = "",
)

/**
 * [MINIS-OZEL 11.5 · 37060] How a team works, as a saved choice.
 *
 * Three modes, not a settings grid, because the user's question is one
 * question: *do I want an object made, a comparison, or a decision?*
 *
 *  - [HANDOFF] — the 11.4 loop: an orchestrator plans, hands single steps to
 *    members, verifies. Best when the deliverable is an artefact (a file, a
 *    fix, a build).
 *  - [FANOUT] — the same task to every member, independently, results side by
 *    side, no conversation. Best for "what do the models each say".
 *  - [COUNCIL] — the debate: independent positions, canonical claims, argument
 *    rounds, one synthesised answer with dissent preserved.
 *
 * [HANDOFF] is the default so an existing saved team keeps its exact behaviour
 * after the update — a team that suddenly started debating would spend ten
 * times the tokens without being asked.
 */
enum class TeamMode(val wire: String) {
    HANDOFF("handoff"),
    FANOUT("fanout"),
    COUNCIL("council"),
    ;

    companion object {
        fun parse(raw: String?): TeamMode =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: HANDOFF
    }
}

data class Team(
    val id: String,
    val name: String,
    val orchestratorProviderInstanceId: String? = null,
    val orchestratorModelId: String? = null,
    val members: List<TeamMember> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** [37060] Defaults to the pre-existing behaviour. */
    val mode: TeamMode = TeamMode.HANDOFF,
    /**
     * [37060] Language the council must write its report in. Null = follow the
     * device. A council of five models writing in five languages produces a
     * report nobody can read, so this is a team property, not per member.
     */
    val language: String? = null,
) {
    companion object {
        /** Mirrors the reference designs: below 2 there is nothing to coordinate. */
        const val MIN_MEMBERS = 2
        const val MAX_MEMBERS = 8
        const val MAX_NAME_CHARS = 60
        const val MAX_ROLE_CHARS = 200
    }
}

/**
 * Reads and writes [Team] definitions. Every method is failure-tolerant by
 * design: a corrupt or half-written file is skipped, never thrown, because a
 * broken team must not be able to make the Ekip screen unusable.
 */
object TeamStore {
    private const val TAG = "TeamStore"
    private const val DIR = "teams"

    fun directory(context: Context): File = File(context.filesDir, DIR)

    private fun fileFor(context: Context, id: String): File = File(directory(context), "$id.json")

    /** Newest first, corrupt files skipped. */
    fun list(context: Context): List<Team> {
        val dir = directory(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { parse(f.readText()) }
                .onFailure { AppLogger.info(TAG, "skip unreadable team file ${f.name}: ${it.message}") }
                .getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun get(context: Context, id: String): Team? =
        runCatching { parse(fileFor(context, id).readText()) }.getOrNull()

    /** Writes atomically: temp file, then rename, so a crash cannot truncate. */
    fun save(context: Context, team: Team): Team {
        val normalized = team.copy(
            name = team.name.trim().take(Team.MAX_NAME_CHARS).ifBlank { "Ekip" },
            members = team.members.take(Team.MAX_MEMBERS),
        )
        val dir = directory(context)
        if (!dir.exists()) dir.mkdirs()
        val target = fileFor(context, normalized.id)
        val tmp = File(dir, "${normalized.id}.json.tmp")
        tmp.writeText(serialize(normalized))
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        return normalized
    }

    fun create(context: Context, name: String): Team =
        save(context, Team(id = UUID.randomUUID().toString(), name = name))

    fun delete(context: Context, id: String) {
        fileFor(context, id).delete()
    }

    // ── JSON ───────────────────────────────────────────────────────────────

    private fun serialize(team: Team): String {
        val members = JSONArray()
        team.members.forEach { m ->
            members.put(
                JSONObject().apply {
                    put("name", m.name)
                    put("role", m.role)
                    m.providerInstanceId?.let { put("providerInstanceId", it) }
                    m.modelId?.let { put("modelId", it) }
                    put("tools", JSONArray(m.tools))
                    put("scope", m.scope)
                },
            )
        }
        return JSONObject().apply {
            put("id", team.id)
            put("name", team.name)
            team.orchestratorProviderInstanceId?.let { put("orchestratorProviderInstanceId", it) }
            team.orchestratorModelId?.let { put("orchestratorModelId", it) }
            put("members", members)
            put("createdAt", team.createdAt)
            // Written only when NOT the default, so a team saved by 11.4 and
            // re-saved by 11.5 is byte-identical unless the user changed
            // something. That keeps diffs and backups readable.
            if (team.mode != TeamMode.HANDOFF) put("mode", team.mode.wire)
            team.language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
        }.toString(2)
    }

    private fun parse(text: String): Team {
        val o = JSONObject(text)
        val membersJson = o.optJSONArray("members") ?: JSONArray()
        val members = (0 until membersJson.length()).mapNotNull { i ->
            membersJson.optJSONObject(i)?.let { m ->
                val toolsJson = m.optJSONArray("tools") ?: JSONArray()
                TeamMember(
                    name = m.optString("name").orEmpty(),
                    role = m.optString("role").orEmpty(),
                    providerInstanceId = m.optString("providerInstanceId").ifBlank { null },
                    modelId = m.optString("modelId").ifBlank { null },
                    tools = (0 until toolsJson.length()).map { toolsJson.optString(it) },
                    scope = m.optString("scope").orEmpty(),
                )
            }
        }.filter { it.name.isNotBlank() }
        return Team(
            id = o.getString("id"),
            name = o.optString("name").ifBlank { "Ekip" },
            orchestratorProviderInstanceId = o.optString("orchestratorProviderInstanceId").ifBlank { null },
            orchestratorModelId = o.optString("orchestratorModelId").ifBlank { null },
            members = members,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            mode = TeamMode.parse(o.optString("mode").ifBlank { null }),
            language = o.optString("language").ifBlank { null },
        )
    }
}
