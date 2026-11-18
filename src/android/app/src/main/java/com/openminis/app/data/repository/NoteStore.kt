package com.openminis.app.data.repository

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [MINIS-OZEL 11.1] Notes — two different things in one place, on purpose.
 *
 * ## The split that matters
 *
 * A NOTE is the agent's own scratchpad: it writes, edits and deletes freely,
 * the same way it would keep a file in its work directory. A VAULT entry is
 * something only the USER can write — credentials, keys, private facts. The
 * agent may read it (that is the point of storing it here) and may never
 * create, change or delete one. Those are two different trust levels, not two
 * kinds of text, so they are two different [Kind]s enforced in the DATA layer
 * rather than in the tool that happens to call it: an agent that finds another
 * route into this class still cannot write a secret, because [writeVault]
 * refuses anything that did not come from the UI.
 *
 * ## Why this is not the 10.x NoteRepository
 *
 * That one was written, never compiled (its `escapeXml` had broken entities and
 * an unterminated raw string, so the module did not build at all), and it
 * modelled every note as belonging to exactly one chat session. Two things were
 * wrong with that: a note the agent learned about the USER belongs to every
 * chat, and a note a panel agent writes belongs to its space. [Scope] carries
 * that distinction and is a string, not a foreign key, so a deleted session
 * never orphans a note.
 *
 * ## Storage
 *
 * `notes.db`, `notes` table, `LIKE` search. No FTS5: Android's FTS5 availability
 * varies with the system SQLite build and a missing module turns every search
 * into an exception — for a few hundred short notes `LIKE` on a lowercased
 * column is fast enough and cannot fail. Bodies are capped ([MAX_BODY_CHARS])
 * so one pasted log cannot make the list unrenderable.
 *
 * Vault bodies are stored as ciphertext (see [VaultCrypto]); the title is
 * plaintext so the list can be drawn without a key operation per row.
 */
class NoteStore(private val context: Context) {

    companion object {
        private const val TAG = "NoteStore"
        private const val DB_NAME = "notes.db"
        private const val DB_VERSION = 1

        /** Bodies are text; this is a ceiling, not a target. */
        const val MAX_BODY_CHARS = 20_000
        const val MAX_TITLE_CHARS = 200

        /**
         * How much of a note the agent is given when notes are auto-injected
         * into a turn. Small on purpose: notes are memory, not context — a
         * dozen full notes would crowd out the actual conversation.
         */
        const val MAX_PROMPT_NOTES = 12
        const val MAX_PROMPT_CHARS_PER_NOTE = 400

        private fun createTableSql(): String =
            "CREATE TABLE notes (" +
                "id TEXT PRIMARY KEY," +
                "scope TEXT NOT NULL," +
                "kind TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "body TEXT NOT NULL," +
                "tags TEXT NOT NULL DEFAULT ''," +
                "author TEXT NOT NULL," +
                "search_blob TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL" + ")"

        @Volatile
        private var shared: NoteStore? = null

        /**
         * Process-wide instance. The agent tool, the notes screen and the edit
         * overlay all read the same list, and three connections to the same
         * SQLite file would give the UI a stale copy after an agent write.
         */
        fun get(context: Context): NoteStore =
            shared ?: synchronized(this) {
                shared ?: NoteStore(context.applicationContext).also { shared = it }
            }

        /** Test hook: drop the process-wide instance. */
        internal fun resetForTest() = synchronized(this) { shared = null }
    }

    /** Who wrote an entry. Drives every permission check in this class. */
    enum class Author { USER, AGENT }

    /** Note = agent may edit. Vault = user only. Never interchangeable. */
    enum class Kind(val wire: String) {
        NOTE("note"),
        VAULT("vault"),
        ;

        companion object {
            fun parse(raw: String?): Kind =
                entries.firstOrNull { it.wire == raw?.trim() } ?: NOTE
        }
    }

    /**
     * Where an entry belongs. Stored as text: `global`, `space:<id>`,
     * `session:<id>`, `project:<folderId>`, or `screen` for edit-mode pins.
     *
     * [MINIS-OZEL 37073] `project:<folderId>` is the shared note scope of a
     * project: every chat filed there sees the same notes, and `global` notes
     * stay visible on top of them ([visibleTo]). Everything else is unchanged,
     * which is why the project scope is one more string and not a new table.
     */
    data class Scope(val wire: String) {
        companion object {
            val GLOBAL = Scope("global")
            val SCREEN = Scope("screen")
            fun space(id: String) = Scope("space:$id")
            fun session(id: String) = Scope("session:$id")

            /** Project-shared notes. Keyed by folder id, NOT by session id. */
            fun project(folderId: String) = Scope("project:$folderId")

            /** True when [wire] names a project's shared scope. */
            fun isProject(wire: String) = wire.startsWith("project:")
        }
    }

    data class Note(
        val id: String,
        val scope: String,
        val kind: Kind,
        val title: String,
        val body: String,
        val tags: List<String>,
        val author: Author,
        val createdAt: Long,
        val updatedAt: Long,
    ) {
        val isVault: Boolean get() = kind == Kind.VAULT
    }

    private class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(createTableSql())
            db.execSQL("CREATE INDEX idx_notes_scope ON notes(scope, kind)")
            db.execSQL("CREATE INDEX idx_notes_updated ON notes(updated_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 is the first version of this schema. The 10.x "notes.db" never
            // shipped in a release, so there is nothing to migrate and no
            // version 0 to guess about.
        }
    }

    private val db: SQLiteDatabase by lazy { Db(context).writableDatabase }

    private val _notes = MutableStateFlow<List<Note>>(emptyList())

    /** Every entry, newest first. UI state; refreshed after each mutation. */
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    // ── reading ─────────────────────────────────────────────────────────────

    suspend fun load(): List<Note> = withContext(Dispatchers.IO) {
        val out = ArrayList<Note>()
        runCatching {
            db.query("notes", null, null, null, null, null, "updated_at DESC").use { c ->
                while (c.moveToNext()) out.add(readRow(c))
            }
        }.onFailure { Log.e(TAG, "load failed: ${it.message}", it) }
        _notes.value = out
        out
    }

    /** Notes for one scope plus everything global — what an agent should see. */
    fun visibleTo(scope: String, kind: Kind = Kind.NOTE): List<Note> =
        _notes.value.filter { it.kind == kind && (it.scope == scope || it.scope == Scope.GLOBAL.wire) }

    fun vaultEntries(): List<Note> = _notes.value.filter { it.isVault }

    fun get(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    /**
     * Case-insensitive substring search over title, body and tags. Vault bodies
     * are excluded from the blob (they are ciphertext at rest and must not be
     * searched by the agent), so a vault row can only match on its title.
     */
    fun search(query: String, kind: Kind? = null): List<Note> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return _notes.value.filter { kind == null || it.kind == kind }
        return _notes.value.filter { n ->
            (kind == null || n.kind == kind) &&
                (n.title.lowercase().contains(q) ||
                    (n.kind == Kind.NOTE && n.body.lowercase().contains(q)) ||
                    n.tags.any { it.contains(q) })
        }
    }

    // ── writing: notes (agent allowed) ──────────────────────────────────────

    /**
     * Creates or updates a NOTE. Returns the stored row.
     *
     * An agent may not overwrite a vault entry through this path — the kind
     * check is here, not in the caller, so every future caller inherits it.
     */
    suspend fun write(
        scope: String,
        title: String,
        body: String,
        tags: List<String> = emptyList(),
        author: Author = Author.AGENT,
        id: String? = null,
    ): Result<Note> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = id?.let { get(it) }
            require(existing == null || existing.kind == Kind.NOTE) {
                "refusing to overwrite a vault entry through the note path"
            }
            val now = System.currentTimeMillis()
            val row = Note(
                id = existing?.id ?: UUID.randomUUID().toString(),
                scope = scope.ifBlank { Scope.GLOBAL.wire },
                kind = Kind.NOTE,
                title = title.trim().take(MAX_TITLE_CHARS).ifBlank { "(untitled)" },
                body = body.take(MAX_BODY_CHARS),
                tags = normalizeTags(tags),
                author = author,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            upsert(row, searchBlob = row.title + " " + row.body + " " + row.tags.joinToString(" "))
            loadQuiet()
            row
        }
    }

    suspend fun deleteNote(id: String): Boolean = withContext(Dispatchers.IO) {
        val row = get(id)
        if (row == null || row.kind != Kind.NOTE) return@withContext false
        val ok = runCatching { db.delete("notes", "id = ? AND kind = ?", arrayOf(id, Kind.NOTE.wire)) > 0 }
            .getOrDefault(false)
        loadQuiet()
        ok
    }

    // ── writing: vault (user only) ──────────────────────────────────────────

    /**
     * Stores a VAULT entry. [fromUi] is a hard requirement, not a convention:
     * only the notes screen passes true. Without it the call throws, which
     * means a prompt-injected agent asking for a secret to be written gets an
     * error it can report rather than a silent success.
     */
    suspend fun writeVault(
        title: String,
        secret: String,
        tags: List<String> = emptyList(),
        fromUi: Boolean = false,
        id: String? = null,
    ): Result<Note> = withContext(Dispatchers.IO) {
        runCatching {
            require(fromUi) { "vault entries can only be written by the user" }
            val existing = id?.let { get(it) }
            require(existing == null || existing.isVault) { "target is not a vault entry" }
            val now = System.currentTimeMillis()
            val row = Note(
                id = existing?.id ?: UUID.randomUUID().toString(),
                scope = Scope.GLOBAL.wire,
                kind = Kind.VAULT,
                title = title.trim().take(MAX_TITLE_CHARS).ifBlank { "(untitled)" },
                body = VaultCrypto.seal(secret),
                tags = normalizeTags(tags),
                author = Author.USER,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            // The search blob deliberately omits the body: a vault entry must
            // not be findable by its CONTENT, only by its name.
            upsert(row, searchBlob = row.title + " " + row.tags.joinToString(" "))
            loadQuiet()
            row
        }
    }

    /**
     * Reads a vault entry's plaintext, or null when it cannot be decrypted
     * (key lost on a device transfer — the entry is then unreadable, not
     * silently blank).
     */
    fun readVault(id: String): String? {
        val row = get(id)?.takeIf { it.isVault } ?: return null
        return VaultCrypto.open(row.body)
    }

    suspend fun deleteVault(id: String, fromUi: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        require(fromUi) { "vault entries can only be deleted by the user" }
        val row = get(id)
        if (row == null || !row.isVault) return@withContext false
        val ok = runCatching { db.delete("notes", "id = ? AND kind = ?", arrayOf(id, Kind.VAULT.wire)) > 0 }
            .getOrDefault(false)
        loadQuiet()
        ok
    }

    // ── prompt contribution ────────────────────────────────────────────────

    /**
     * The notes an agent gets for free at the start of a turn.
     *
     * Only NOTES, never VAULT: a secret must never ride along in a prompt the
     * user did not ask for. That is the whole reason the vault is separated —
     * auto-injected secrets would make the split decorative.
     */
    fun promptNotes(scope: String): String {
        // The caller builds a system prompt, possibly before the notes screen
        // was ever opened, so the in-memory list may still be empty. Reading it
        // once here is what makes the difference between "the agent has notes"
        // and "the agent has notes only after the user visited a screen".
        // Guarded and one-shot: `everLoaded` makes this a no-op afterwards.
        if (!everLoaded) runCatching { loadBlocking() }
        val rows = visibleTo(scope).take(MAX_PROMPT_NOTES)
        if (rows.isEmpty()) return ""
        return buildString {
            append("Your notes (yours to keep and edit; use note_write / note_read):\n")
            rows.forEach { n ->
                append("- [").append(n.id.take(8)).append("] ").append(n.title)
                val body = n.body.replace(Regex("\\s+"), " ").trim()
                if (body.isNotEmpty()) {
                    append(": ").append(body.take(MAX_PROMPT_CHARS_PER_NOTE))
                    if (body.length > MAX_PROMPT_CHARS_PER_NOTE) append("…")
                }
                append('\n')
            }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun loadQuiet() {
        runCatching { loadBlocking() }.onFailure { Log.e(TAG, "reload failed: ${it.message}", it) }
    }

    @Volatile
    private var everLoaded = false

    private fun loadBlocking() {
        val out = ArrayList<Note>()
        db.query("notes", null, null, null, null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) out.add(readRow(c))
        }
        _notes.value = out
        everLoaded = true
    }

    private fun upsert(row: Note, searchBlob: String) {
        val values = android.content.ContentValues().apply {
            put("id", row.id)
            put("scope", row.scope)
            put("kind", row.kind.wire)
            put("title", row.title)
            put("body", row.body)
            put("tags", row.tags.joinToString(","))
            put("author", row.author.name.lowercase())
            put("search_blob", searchBlob.lowercase())
            put("created_at", row.createdAt)
            put("updated_at", row.updatedAt)
        }
        db.insertWithOnConflict("notes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun readRow(c: Cursor): Note {
        fun col(name: String) = c.getColumnIndexOrThrow(name)
        return Note(
            id = c.getString(col("id")),
            scope = c.getString(col("scope")),
            kind = Kind.parse(c.getString(col("kind"))),
            title = c.getString(col("title")),
            body = c.getString(col("body")),
            tags = c.getString(col("tags")).split(",").filter { it.isNotBlank() },
            author = if (c.getString(col("author")) == "user") Author.USER else Author.AGENT,
            createdAt = c.getLong(col("created_at")),
            updatedAt = c.getLong(col("updated_at")),
        )
    }

    private fun normalizeTags(tags: List<String>): List<String> =
        tags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().take(20)
}
