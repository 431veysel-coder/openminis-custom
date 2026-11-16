package com.openminis.app.data.repository

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent notes for agents — per-session, searchable, versioned.
 *
 * Schema (notes.db v1):
 *   id TEXT PRIMARY KEY
 *   session_id TEXT NOT NULL
 *   title TEXT NOT NULL DEFAULT ''
 *   content TEXT NOT NULL DEFAULT ''
 *   tags TEXT NOT NULL DEFAULT ''  -- comma-separated
 *   version INTEGER NOT NULL DEFAULT 1
 *   created_at INTEGER NOT NULL
 *   updated_at INTEGER NOT NULL
 *
 * Indexes:
 *   idx_notes_session ON notes(session_id)
 *   idx_notes_updated ON notes(updated_at DESC)
 */
class NoteRepository(private val context: Context) {

    companion object {
        private const val TAG = "NoteRepository"
        private const val DB_NAME = "notes.db"
        private const val DB_VERSION = 1
        private const val MAX_NOTES_IN_PROMPT = 20
        private const val MAX_NOTE_PREVIEW_CHARS = 500
        private fun createNotesTableSql(): String = """
        CREATE TABLE notes (
            id TEXT PRIMARY KEY,
            session_id TEXT NOT NULL,
            title TEXT NOT NULL DEFAULT '',
            content TEXT NOT NULL DEFAULT '',
            tags TEXT NOT NULL DEFAULT '',
            version INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
    """.trimIndent()
    }

    // Background scope for writes that must survive config changes
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // State
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val db: SQLiteDatabase by lazy { NoteDbHelper(context).writableDatabase }
    private val notesLock = Any()
    private var mutationSeq = 0L
    private val reloadRequests = Channel<String>(Channel.CONFLATED)
    private val forceReloadPending = AtomicBoolean(false)

    init {
        runCatching { loadAll() }.onFailure {
            Log.e(TAG, "loadAll failed: ${it.message}", it)
        }
        startReloadWorker()
    }

    // -- Data Types --

    data class Note(
        val id: String = UUID.randomUUID().toString(),
        val sessionId: String,
        val title: String = "",
        val content: String = "",
        val tags: String = "",           // comma-separated, normalized lowercase
        val version: Int = 1,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    ) {
        val tagList: List<String>
            get() = tags.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()

        val preview: String
            get() = if (content.length <= MAX_NOTE_PREVIEW_CHARS) content
            else content.substring(0, MAX_NOTE_PREVIEW_CHARS) + "…"
    }

    // -- CRUD --

    /** Create a new note. Returns the created note or null if sessionId is blank. */
    fun create(sessionId: String, title: String = "", content: String = "", tags: String = ""): Note? {
        if (sessionId.isBlank()) return null
        val normalizedTags = normalizeTags(tags)
        val note = Note(
            sessionId = sessionId,
            title = title.trim(),
            content = content.trim(),
            tags = normalizedTags,
        )
        insertDb(note)
        mutateNotes { it + note }
        Log.i(TAG, "Created note: ${note.id} for session $sessionId")
        return note
    }

    /** Read a note by id. */
    fun get(id: String): Note? = _notes.value.find { it.id == id }

    /** Update note fields. Only non-null values are changed. Version is bumped. */
    fun update(id: String, title: String? = null, content: String? = null, tags: String? = null): Boolean {
        val current = _notes.value.find { it.id == id } ?: return false
        val updated = current.copy(
            title = title?.trim() ?: current.title,
            content = content?.trim() ?: current.content,
            tags = tags?.let { normalizeTags(it) } ?: current.tags,
            version = current.version + 1,
            updatedAt = System.currentTimeMillis(),
        )
        db.execSQL(
            "UPDATE notes SET title=?, content=?, tags=?, version=?, updated_at=? WHERE id=?",
            arrayOf<Any>(updated.title, updated.content, updated.tags, updated.version, updated.updatedAt, id)
        )
        mutateNotes { list -> list.map { if (it.id == id) updated else it } }
        return true
    }

    /** Delete a note. */
    fun delete(id: String) {
        db.execSQL("DELETE FROM notes WHERE id=?", arrayOf(id))
        mutateNotes { list -> list.filter { it.id != id } }
        Log.i(TAG, "Deleted note: $id")
    }

    /** List notes for a session, newest first. */
    fun listForSession(sessionId: String): List<Note> =
        _notes.value.filter { it.sessionId == sessionId }
            .sortedByDescending { it.updatedAt }

    /** All notes across all sessions, newest first. */
    fun allNotes(): List<Note> =
        _notes.value.sortedByDescending { it.updatedAt }

    /** Search notes by keyword (title + content + tags) within a session. */
    fun search(sessionId: String, query: String): List<Note> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return listForSession(sessionId)
        return _notes.value.filter { it.sessionId == sessionId }
            .filter { n ->
                n.title.lowercase().contains(q) ||
                n.content.lowercase().contains(q) ||
                n.tagList.any { it.contains(q) }
            }
            .sortedByDescending { it.updatedAt }
    }

    /** Get notes formatted for system prompt injection (limited count, preview). */
    fun promptFragment(sessionId: String): String? {
        val sessionNotes = listForSession(sessionId)
        if (sessionNotes.isEmpty()) return null

        val selected = sessionNotes.take(MAX_NOTES_IN_PROMPT)
        val hasMore = sessionNotes.size > selected.size

        val xml = buildString {
            append("<agent_notes>\n")
            for (note in selected) {
                append("  <note>\n")
                append("    <id>").append(escapeXml(note.id)).append("</id>\n")
                if (note.title.isNotBlank()) {
                    append("    <title>").append(escapeXml(note.title)).append("</title>\n")
                }
                append("    <content>").append(escapeXml(note.preview)).append("</content>\n")
                if (note.tags.isNotBlank()) {
                    append("    <tags>").append(escapeXml(note.tags)).append("</tags>\n")
                }
                append("    <updated>").append(note.updatedAt).append("</updated>\n")
                append("  </note>\n")
            }
            append("</agent_notes>")
        }

        return buildString {
            append("Agent Notes (persistent, per-session):\n")
            append("You can read, create, update, delete, and search these notes using the note tools.\n")
            append("Notes survive across conversations in the same session.\n\n")
            append(xml)
            if (hasMore) {
                append("\n\n... and ").append(sessionNotes.size - selected.size).append(" more notes (use search to find them).")
            }
        }
    }

    // -- Reload --

    fun reloadFromDisk() { requestReload("reloadFromDisk", force = true) }

    private fun requestReload(reason: String, force: Boolean = false) {
        if (force) forceReloadPending.set(true)
        reloadRequests.trySend(reason)
    }

    private fun startReloadWorker() {
        backgroundScope.launch {
            for (reason in reloadRequests) {
                val force = forceReloadPending.getAndSet(false)
                runCatching {
                    val started = System.currentTimeMillis()
                    loadAllCommitted()
                    Log.i(TAG, "reload reason=$reason force=$force notes=${_notes.value.size} tookMs=${System.currentTimeMillis() - started}")
                }.onFailure {
                    Log.e(TAG, "background reload failed reason=$reason: ${it.message}", it)
                }
            }
        }
    }

    private fun loadAllCommitted() {
        repeat(3) { if (loadAll(requireSeq = true)) return }
        loadAll(requireSeq = false)
    }

    private fun loadAll(requireSeq: Boolean = false): Boolean {
        val seqAtStart = synchronized(notesLock) { mutationSeq }
        val dbNotes = mutableListOf<Note>()

        db.rawQuery("SELECT * FROM notes ORDER BY updated_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    dbNotes.add(Note(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                        tags = cursor.getString(cursor.getColumnIndexOrThrow("tags")),
                        version = cursor.getInt(cursor.getColumnIndexOrThrow("version")),
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                    ))
                } catch (t: Throwable) {
                    Log.e(TAG, "skipping unreadable note row: ${t.message}", t)
                }
            }
        }

        synchronized(notesLock) {
            if (requireSeq && mutationSeq != seqAtStart) return false
            _notes.value = dbNotes
        }
        return true
    }

    // -- Internal --

    private inline fun mutateNotes(transform: (List<Note>) -> List<Note>) {
        synchronized(notesLock) {
            _notes.value = transform(_notes.value)
            mutationSeq++
        }
    }

    private fun insertDb(note: Note) {
        db.execSQL(
            """INSERT INTO notes (id, session_id, title, content, tags, version, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any>(
                note.id, note.sessionId, note.title, note.content,
                note.tags, note.version, note.createdAt, note.updatedAt
            )
        )
    }

    private fun normalizeTags(raw: String): String =
        raw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().joinToString(",")

    private fun escapeXml(text: String): String =
        text.replace("&", "&")
            .replace("<", "<")
            .replace(">", ">")
            .replace("\"", """)
            .replace("'", "&apos;")

    // -- Database Helper --

    private class NoteDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(createNotesTableSql())
            db.execSQL("CREATE INDEX idx_notes_session ON notes(session_id)")
            db.execSQL("CREATE INDEX idx_notes_updated ON notes(updated_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 is initial; future migrations go here
        }
    }
}