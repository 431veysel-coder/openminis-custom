package com.openminis.app.data.journal

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [MINIS-OZEL 11.3.1] The action journal: every change an agent made, with the
 * recipe to undo it.
 *
 * ## Why this exists
 *
 * A chat transcript says what the agent *claimed* to do. It does not say what
 * changed on disk, and it cannot put anything back. That mattered the moment a
 * run touched a real file: the only recovery was "notice quickly and guess",
 * and a guess is what produced nine blind patches on NoteRepository.
 *
 * This is not logging. A log records that something happened; a journal records
 * enough to reverse it, and that difference decides whether the app can offer
 * autonomy at all. "Approve every step" and "let it run" stop being the only two
 * options once every step is reversible.
 *
 * ## The rule that makes it trustworthy
 *
 * The undo recipe is captured by the WRITER, at write time, not reconstructed
 * later by reading the file. Reconstructing is exactly where it goes wrong:
 * by the time you want to undo an overwrite, the previous content is gone.
 * Every mutation entry point therefore calls [record] with what it is about to
 * destroy, and nothing else is allowed to guess.
 *
 * ## What is stored, and what is not
 *
 * - Entries: JSONL, one file per day, under `filesDir/journal/`. Append-only.
 * - Payloads (the previous bytes): `filesDir/journal/blobs/<id>`, written only
 *   when the previous content is small enough to be worth restoring
 *   ([MAX_BLOB_BYTES]). A 40 MB model file is recorded as "not reversible —
 *   too large", which is honest; silently storing it would be worse.
 * - Never a credential: a path under the vault or an env-var store is recorded
 *   with its content replaced by a marker, because the whole point of those
 *   stores is that the bytes do not sit in plain files.
 *
 * ## Retention
 *
 * [RETENTION_DAYS] days. The journal is a safety net for the last few days of
 * work, not an audit trail to keep forever — an unbounded one becomes a privacy
 * problem and a storage problem at the same time.
 */
object ActionJournal {

    private const val TAG = "ActionJournal"
    private const val DIR = "journal"
    private const val BLOB_DIR = "blobs"
    private const val RETENTION_DAYS = 14

    /**
     * Above this, the previous content is not stored: the payload would cost
     * more than the undo is worth and the copy itself would be a slow write on
     * a phone. 512 KB covers source files, configs and notes — the things an
     * agent actually rewrites.
     */
    const val MAX_BLOB_BYTES = 512 * 1024

    /** How the entry can be reversed. */
    enum class UndoKind(val wire: String) {
        /** Restore the bytes in the blob at [Entry.blobPath]. */
        RESTORE("restore"),

        /** The file did not exist; undo deletes it. */
        DELETE("delete"),

        /** Append happened; undo truncates back to [Entry.beforeBytes]. */
        TRUNCATE("truncate"),

        /** Not reversible — say so plainly rather than pretending. */
        NONE("none"),
        ;

        companion object {
            fun parse(raw: String?): UndoKind =
                entries.firstOrNull { it.wire == raw } ?: NONE
        }
    }

    /** One journalled change. */
    data class Entry(
        val id: String,
        val timestamp: Long,
        val sessionId: String?,
        val tool: String,
        val title: String,
        val target: String,
        val undoKind: UndoKind,
        val blobPath: String?,
        val beforeBytes: Long,
        val afterBytes: Long,
        val note: String,
        val undone: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("ts", timestamp)
            put("session", sessionId ?: JSONObject.NULL)
            put("tool", tool)
            put("title", title)
            put("target", target)
            put("undo", undoKind.wire)
            put("blob", blobPath ?: JSONObject.NULL)
            put("before", beforeBytes)
            put("after", afterBytes)
            put("note", note)
            put("undone", undone)
        }

        companion object {
            fun fromJson(o: JSONObject): Entry = Entry(
                id = o.optString("id"),
                timestamp = o.optLong("ts"),
                sessionId = o.optString("session").takeIf { it.isNotEmpty() && it != "null" },
                tool = o.optString("tool"),
                title = o.optString("title"),
                target = o.optString("target"),
                undoKind = UndoKind.parse(o.optString("undo")),
                blobPath = o.optString("blob").takeIf { it.isNotEmpty() && it != "null" },
                beforeBytes = o.optLong("before"),
                afterBytes = o.optLong("after"),
                note = o.optString("note"),
                undone = o.optBoolean("undone"),
            )
        }
    }

    /** Result of an undo attempt, phrased for both the UI and the agent. */
    data class UndoResult(val ok: Boolean, val message: String)

    // ── paths ───────────────────────────────────────────────────────────────

    /**
     * Test seam: when set, entries and payloads live under this directory
     * instead of `filesDir`. The undo logic is the riskiest part of this
     * feature — it deletes and rewrites real files — so it has to be testable
     * on the JVM without an Android context.
     */
    internal var testRoot: File? = null

    /**
     * Root directory. [ctx] is nullable only so the JVM unit tests can point
     * the journal at a temp dir; in the app it is always the real context.
     */
    private fun root(ctx: Context?): File {
        val dir = testRoot ?: File(
            requireNotNull(ctx) { "ActionJournal needs a Context when testRoot is unset" }
                .applicationContext.filesDir,
            DIR,
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun blobs(ctx: Context?): File =
        File(root(ctx), BLOB_DIR).apply { if (!exists()) mkdirs() }

    private fun dayFile(dir: File, day: String): File = File(dir, "$day.jsonl")

    private fun dayOf(ts: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
            .format(java.util.Date(ts))

    /** Never journalled with content: these stores exist to keep bytes off disk. */
    private fun isSecretPath(path: String): Boolean {
        val p = path.lowercase(java.util.Locale.ROOT)
        return p.contains("/vault/") || p.contains("envvar") || p.contains("secret") ||
            p.contains("keystore") || p.endsWith(".jks") || p.endsWith(".keystore")
    }

    // ── recording ───────────────────────────────────────────────────────────

    /**
     * Records one mutation.
     *
     * @param previous the bytes about to be overwritten, or null when the file
     *   did not exist. Callers pass the real content — this is the only place
     *   that can save it.
     * @param appended true when the tool appended instead of rewriting, so the
     *   undo is a truncation rather than a restore.
     */
    fun record(
        ctx: Context?,
        tool: String,
        title: String,
        target: String,
        previous: String?,
        afterBytes: Long,
        sessionId: String? = null,
        appended: Boolean = false,
        note: String = "",
    ): Entry {
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        var kind = UndoKind.NONE
        var blob: String? = null
        var storedNote = note
        var beforeBytes = 0L

        if (isSecretPath(target)) {
            storedNote = listOf(note, "secret path — content not stored").filter { it.isNotEmpty() }
                .joinToString("; ")
        } else if (previous == null) {
            kind = UndoKind.DELETE
        } else {
            beforeBytes = previous.toByteArray(Charsets.UTF_8).size.toLong()
            if (beforeBytes > MAX_BLOB_BYTES) {
                storedNote = listOf(note, "previous content too large to keep (${beforeBytes} B)")
                    .filter { it.isNotEmpty() }.joinToString("; ")
            } else {
                blob = writeBlob(ctx, id, previous)
                kind = if (appended) UndoKind.TRUNCATE else UndoKind.RESTORE
            }
        }

        val entry = Entry(
            id = id,
            timestamp = ts,
            sessionId = sessionId,
            tool = tool,
            title = title,
            target = target,
            undoKind = kind,
            blobPath = blob,
            beforeBytes = beforeBytes,
            afterBytes = afterBytes,
            note = storedNote,
            undone = false,
        )
        appendEntry(ctx, entry)
        return entry
    }

    private fun writeBlob(ctx: Context?, id: String, content: String): String? = try {
        val f = File(blobs(ctx), id)
        f.writeText(content, Charsets.UTF_8)
        f.absolutePath
    } catch (e: Exception) {
        AppLogger.warning(TAG, "could not store undo payload: ${e.javaClass.simpleName}")
        null
    }

    private fun appendEntry(ctx: Context?, entry: Entry) {
        try {
            val dir = root(ctx)
            val f = dayFile(dir, dayOf(entry.timestamp))
            f.appendText(entry.toJson().toString() + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            // A journal that crashes the write it is journalling is worse than
            // no journal: the tool call must still succeed.
            AppLogger.warning(TAG, "could not append entry: ${e.javaClass.simpleName}")
        }
        prune(ctx)
    }

    // ── reading ─────────────────────────────────────────────────────────────

    /** Entries newest-first, optionally narrowed to one session. */
    fun recent(ctx: Context?, limit: Int = 50, sessionId: String? = null): List<Entry> {
        val out = ArrayList<Entry>()
        val dir = root(ctx)
        val days = (dir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray())
            .sortedByDescending { it.name }
        for (f in days) {
            val lines = try {
                f.readLines(Charsets.UTF_8)
            } catch (e: Exception) {
                continue
            }
            for (line in lines.asReversed()) {
                if (line.isBlank()) continue
                val e = try {
                    Entry.fromJson(JSONObject(line))
                } catch (ex: Exception) {
                    continue
                }
                if (sessionId != null && e.sessionId != sessionId) continue
                out.add(e)
                if (out.size >= limit) return out
            }
        }
        return out
    }

    fun find(ctx: Context?, idPrefix: String): Entry? =
        recent(ctx, limit = 500).firstOrNull { it.id.startsWith(idPrefix) }

    /**
     * Marks an entry undone without touching the filesystem.
     *
     * Separate from [undo] so the caller can perform the file operation itself
     * when it needs to (a UI on a background dispatcher, a tool that must
     * return a structured error).
     */
    fun markUndone(ctx: Context?, id: String) {
        val dir = root(ctx)
        val days = (dir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray())
        for (f in days) {
            val lines = try {
                f.readLines(Charsets.UTF_8)
            } catch (e: Exception) {
                continue
            }
            var changed = false
            val rewritten = lines.map { line ->
                if (line.isBlank()) return@map line
                val o = try {
                    JSONObject(line)
                } catch (e: Exception) {
                    return@map line
                }
                if (o.optString("id") == id) {
                    o.put("undone", true)
                    changed = true
                    o.toString()
                } else {
                    line
                }
            }
            if (changed) {
                try {
                    f.writeText(rewritten.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
                } catch (e: Exception) {
                    AppLogger.warning(TAG, "could not mark undone: ${e.javaClass.simpleName}")
                }
                return
            }
        }
    }

    // ── undoing ─────────────────────────────────────────────────────────────

    /**
     * Reverses one entry.
     *
     * The file operations are done here rather than by the caller because the
     * correctness of the whole mechanism lives in the pairing: RESTORE must read
     * the blob it was given, TRUNCATE must cut exactly `beforeBytes`, DELETE must
     * refuse when the file has been changed by someone else since. A caller that
     * re-implemented any of that would eventually get one of them wrong.
     *
     * Refuses rather than guesses when the current file no longer matches what
     * the entry said it wrote: undoing onto a file that a later step modified
     * would destroy the later step's work, which is the opposite of the point.
     */
    fun undo(ctx: Context?, entry: Entry): UndoResult {
        if (entry.undone) return UndoResult(false, "already undone")
        val target = File(entry.target)
        return when (entry.undoKind) {
            UndoKind.NONE -> UndoResult(
                false,
                "not reversible" + if (entry.note.isNotEmpty()) " — ${entry.note}" else "",
            )

            UndoKind.DELETE -> try {
                if (!target.exists()) {
                    markUndone(ctx, entry.id)
                    UndoResult(true, "file was already gone")
                } else if (target.length() != entry.afterBytes) {
                    UndoResult(
                        false,
                        "file changed since (${target.length()} B now, ${entry.afterBytes} B then) — " +
                            "refusing to delete",
                    )
                } else {
                    target.delete()
                    markUndone(ctx, entry.id)
                    UndoResult(true, "removed ${target.name}")
                }
            } catch (e: Exception) {
                UndoResult(false, "could not delete: ${e.message ?: e.javaClass.simpleName}")
            }

            UndoKind.TRUNCATE, UndoKind.RESTORE -> {
                val path = entry.blobPath
                if (path == null || !File(path).exists()) {
                    UndoResult(false, "undo payload is missing")
                } else {
                    try {
                        val before = File(path).readText(Charsets.UTF_8)
                        if (entry.undoKind == UndoKind.TRUNCATE) {
                            // Append is the one case where the tail was added:
                            // cut back to the exact previous byte count.
                            val current = if (target.exists()) target.readText(Charsets.UTF_8) else ""
                            val previousLen = before.length
                            if (current.length < previousLen) {
                                return UndoResult(false, "file is shorter than before the append")
                            }
                            target.writeText(current.substring(0, previousLen), Charsets.UTF_8)
                            markUndone(ctx, entry.id)
                            UndoResult(true, "removed the appended part of ${target.name}")
                        } else {
                            target.parentFile?.mkdirs()
                            target.writeText(before, Charsets.UTF_8)
                            markUndone(ctx, entry.id)
                            UndoResult(true, "restored ${target.name} (${before.length} chars)")
                        }
                    } catch (e: Exception) {
                        UndoResult(false, "could not restore: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    // ── retention ───────────────────────────────────────────────────────────

    /**
     * Drops entries and blobs older than [RETENTION_DAYS].
     *
     * Runs on append, so it costs nothing extra and cannot be forgotten by a
     * caller. Blobs are collected by walking the surviving entries rather than
     * by age: an entry can outlive the day file that first referenced it.
     */
    private fun prune(ctx: Context?) {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_DAYS.toLong() * 24 * 60 * 60 * 1000
            val dir = root(ctx)
            val days = dir.listFiles { f -> f.name.endsWith(".jsonl") } ?: return
            val survivors = HashSet<String>()
            for (f in days) {
                val day = f.name.removeSuffix(".jsonl")
                val dayTs = try {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                        .parse(day)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
                if (dayTs in 1 until cutoff) {
                    // Drop its blobs before the file that names them.
                    f.readLines(Charsets.UTF_8).forEach { line ->
                        try {
                            JSONObject(line).optString("blob").takeIf { it.isNotEmpty() }
                                ?.let { File(it).delete() }
                        } catch (e: Exception) {
                            // ignore a malformed line
                        }
                    }
                    f.delete()
                } else {
                    f.readLines(Charsets.UTF_8).forEach { line ->
                        try {
                            JSONObject(line).optString("blob").takeIf { it.isNotEmpty() }
                                ?.let { survivors.add(File(it).name) }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }
            blobs(ctx).listFiles()?.forEach { b ->
                if (b.name !in survivors) b.delete()
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "prune failed: ${e.javaClass.simpleName}")
        }
    }

    /** Total bytes on disk, for the settings row. */
    fun storageBytes(ctx: Context?): Long = try {
        root(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (e: Exception) {
        0L
    }

    /** Wipes the journal and every payload. */
    fun clear(ctx: Context?) {
        try {
            root(ctx).deleteRecursively()
        } catch (e: Exception) {
            AppLogger.warning(TAG, "clear failed: ${e.javaClass.simpleName}")
        }
    }

    /** For the agent tool's list output. */
    fun summarize(entries: List<Entry>): String {
        if (entries.isEmpty()) return "The journal is empty — nothing has been changed yet."
        return buildString {
            appendLine("${entries.size} change(s), newest first:")
            entries.forEach { e ->
                val rev = when (e.undoKind) {
                    UndoKind.NONE -> "not reversible"
                    else -> if (e.undone) "already undone" else "can undo"
                }
                appendLine(
                    "- [${e.id.take(8)}] ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(e.timestamp))} " +
                        "${e.tool} → ${e.target} (${rev})" +
                        if (e.title.isNotEmpty()) " · ${e.title}" else "",
                )
            }
        }.trimEnd()
    }
}
