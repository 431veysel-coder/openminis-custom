package com.openminis.app.agent.team

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [MINIS-OZEL 11.5 · 37050] The task board on disk, one board per TEAM.
 *
 * ## Why per team and not global
 *
 * The board is what a team decided to do. A global board would merge two teams'
 * follow-ups into one list with no way to tell whose is whose — and the first
 * symptom would be a member of team A picking up team B's task because it looked
 * unassigned. Per team also means "delete this team" can take its board with it
 * without a second thought about orphans.
 *
 * ## Why files and not Room
 *
 * Same reasoning as `TeamStore` and `TeamRunStore`: written rarely, read whole,
 * never queried by field, and a corrupt file must be skipped rather than throw.
 * A schema migration for a list of at most two hundred rows is risk without
 * benefit.
 *
 * ## The write discipline
 *
 * Every mutation goes through [update], which reads the current board, runs a pure
 * transition from [TaskBoard], and writes back atomically (temp file then rename).
 * That is slightly more I/O than mutating in memory, and it is what makes the
 * evidence rule unbypassable: there is no "just save this object" entry point that
 * could skip it.
 */
object TaskStore {

    private const val TAG = "TaskStore"
    private const val DIR = "task-boards"

    fun directory(context: Context): File = File(context.filesDir, DIR)
    private fun fileFor(context: Context, teamId: String): File =
        File(directory(context), "${safeId(teamId)}.json")

    /**
     * A team id is a UUID today, so this changes nothing — but a path segment
     * built from an id must never be able to escape the directory, and a future
     * import that carries a different id shape must not be able to either.
     */
    private fun safeId(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { "team" }

    /** The board for [teamId]; an absent or unreadable file is an empty board. */
    fun load(context: Context, teamId: String): TaskBoard {
        val file = fileFor(context, teamId)
        if (!file.exists()) return TaskBoard()
        return runCatching { parse(file.readText()) }
            .onFailure { AppLogger.info(TAG, "unreadable board ${file.name}: ${it.message}") }
            .getOrDefault(TaskBoard())
    }

    /** [37050] Boards of every team, for a global count without N screens. */
    fun loadAll(context: Context): Map<String, TaskBoard> {
        val dir = directory(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        return files.mapNotNull { f ->
            val teamId = f.name.removeSuffix(".json")
            runCatching { teamId to parse(f.readText()) }.getOrNull()
        }.toMap()
    }

    /**
     * Apply one transition and persist it.
     *
     * [block] receives the current board and returns a new one plus an optional
     * refusal. A refusal means NOTHING IS WRITTEN: a failed "mark done" must not
     * quietly persist the state it was refused in, and it must not bump
     * `updatedAtMs` either, or the list would reorder on a no-op.
     */
    fun update(
        context: Context,
        teamId: String,
        block: (TaskBoard) -> Pair<TaskBoard, String>,
    ): Pair<TaskBoard, String> {
        val current = load(context, teamId)
        val (next, refusal) = block(current)
        if (refusal.isNotEmpty()) return current to refusal
        if (next == current) return current to ""
        write(context, teamId, next)
        return next to ""
    }

    /**
     * Add a task and persist it.
     *
     * The three-way result of [TaskBoard.add] is collapsed to the two the caller
     * needs: the board as it now stands, and a refusal if nothing was written. The
     * stored task itself is read back out of the board by id — returning the
     * caller's own object would hand back the un-trimmed version.
     */
    fun add(context: Context, teamId: String, task: TeamTask): Pair<TaskBoard, String> =
        update(context, teamId) { board ->
            val (next, stored, refusal) = board.add(task)
            if (stored == null) return@update board to refusal
            next to ""
        }

    fun save(context: Context, teamId: String, board: TaskBoard) {
        write(context, teamId, board)
    }

    fun delete(context: Context, teamId: String) {
        fileFor(context, teamId).delete()
    }

    /** A new task id. Short enough to type in a tool call, unique enough per board. */
    fun newId(): String = "t_" + UUID.randomUUID().toString().replace("-", "").take(8)

    // ── JSON ───────────────────────────────────────────────────────────────

    private fun write(context: Context, teamId: String, board: TaskBoard) {
        val dir = directory(context)
        if (!dir.exists()) dir.mkdirs()
        val target = fileFor(context, teamId)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(serialize(board))
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    fun serialize(board: TaskBoard): String {
        val tasks = JSONArray()
        board.tasks.forEach { t ->
            val evidence = JSONArray()
            t.evidence.forEach { e ->
                evidence.put(
                    JSONObject().apply {
                        put("kind", e.kind.wire)
                        put("value", e.value)
                        if (e.by.isNotBlank()) put("by", e.by)
                        put("atMs", e.atMs)
                    },
                )
            }
            tasks.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    if (t.detail.isNotBlank()) put("detail", t.detail)
                    put("state", t.state.wire)
                    if (t.owner.isNotBlank()) put("owner", t.owner)
                    if (t.runId.isNotBlank()) put("runId", t.runId)
                    if (t.fromClaim.isNotBlank()) put("fromClaim", t.fromClaim)
                    if (t.createdBy.isNotBlank()) put("createdBy", t.createdBy)
                    put("createdAtMs", t.createdAtMs)
                    put("updatedAtMs", t.updatedAtMs)
                    if (evidence.length() > 0) put("evidence", evidence)
                },
            )
        }
        return JSONObject().apply {
            put("version", 1)
            put("tasks", tasks)
        }.toString(2)
    }

    fun parse(text: String): TaskBoard {
        val obj = JSONObject(text)
        val arr = obj.optJSONArray("tasks") ?: return TaskBoard()
        val tasks = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            val title = o.optString("title").trim()
            // A row with no id cannot be referenced, and one with no title cannot
            // be read: both are skipped rather than repaired, because inventing
            // either would put a made-up task in front of the user.
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            val evArr = o.optJSONArray("evidence")
            val evidence = if (evArr == null) {
                emptyList()
            } else {
                (0 until evArr.length()).mapNotNull { k ->
                    val e = evArr.optJSONObject(k) ?: return@mapNotNull null
                    val value = e.optString("value")
                    if (value.isBlank()) return@mapNotNull null
                    TaskEvidence(
                        kind = EvidenceKind.parse(e.optString("kind")),
                        value = value,
                        by = e.optString("by"),
                        atMs = e.optLong("atMs", 0L),
                    )
                }
            }
            TeamTask(
                id = id,
                title = title.take(TaskBoard.MAX_TITLE),
                detail = o.optString("detail").take(TaskBoard.MAX_DETAIL),
                state = TaskState.parse(o.optString("state")),
                owner = o.optString("owner"),
                runId = o.optString("runId"),
                fromClaim = o.optString("fromClaim"),
                evidence = evidence.take(TaskBoard.MAX_EVIDENCE),
                createdAtMs = o.optLong("createdAtMs", 0L),
                updatedAtMs = o.optLong("updatedAtMs", 0L),
                createdBy = o.optString("createdBy"),
            )
        }.take(TaskBoard.MAX_TASKS)
        return TaskBoard(tasks)
    }
}
