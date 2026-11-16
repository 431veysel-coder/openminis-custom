package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.OzelFeatures
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.NoteStore
import org.json.JSONObject

/**
 * [MINIS-OZEL 11.1] `note` — the agent's own notebook, plus a read-only window
 * onto the user's vault.
 *
 * ## Why one tool and not four
 *
 * `note_read`, `note_write`, `vault_list`, `vault_read` as separate tools would
 * occupy four slots in every prompt and the model would still have to learn
 * which is which. One tool with an `action` enum is the same shape the rest of
 * this app already uses (`browser_use`, `shell_execute`), and the permission
 * rules live in [NoteStore] rather than here — a model that ignores the
 * description cannot escalate, it just gets an error back.
 *
 * ## What the agent can and cannot do
 *
 * - NOTE: list, read, write, append, search, delete. Free.
 * - VAULT: list names always; read a value ONLY when the user turned on
 *   Settings › Minis Özel › "Agent may read the vault". Creating, editing or
 *   deleting a vault entry is never possible from a tool — [NoteStore.writeVault]
 *   and [NoteStore.deleteVault] require a call only the UI makes.
 *
 * The vault read gate is a user setting rather than a per-call prompt because a
 * secret is usually needed mid-task: an approval card per read would either be
 * tapped blindly by the third time or stall the run. The setting is OFF by
 * default and says plainly that enabling it sends the secret to the provider.
 */
object NoteTool {

    const val NAME = "note"

    private const val MAX_RESULTS = 20

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Your own notebook, and a read-only view of the user's vault. " +
            "Use it to keep facts you want across turns (a decision, a TODO, something the user " +
            "told you about themselves) instead of repeating them in chat. " +
            "Actions: list (recent notes for this chat plus global ones), " +
            "read (full body of one note by id), " +
            "write (create or replace a note: pass id to replace, or title+content for a new one), " +
            "append (add a line to an existing note), " +
            "search (substring over titles, bodies and tags), " +
            "delete (remove a note you wrote), " +
            "vault_list (NAMES of the user's vault entries only), " +
            "vault_read (the secret itself — only works if the user allowed it in settings). " +
            "You can never create, edit or delete a vault entry: those belong to the user.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does, shown to the user " +
                    "(e.g. 'Save project decision to notes'). Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "What to do",
                enumValues = listOf(
                    "list", "read", "write", "append", "search", "delete",
                    "vault_list", "vault_read",
                ),
            ),
            "id" to AgentToolParam(
                "string",
                "Note id (read / write-to-replace / append / delete). The 8-character prefix " +
                    "shown in list output is enough.",
            ),
            "title" to AgentToolParam(
                "string",
                "Note title. Required for a new note; keep it short and specific.",
            ),
            "content" to AgentToolParam(
                "string",
                "Note body (write / append). Markdown is fine.",
            ),
            "query" to AgentToolParam(
                "string",
                "Text to look for (search). Case-insensitive substring.",
            ),
            "tags" to AgentToolParam(
                "string",
                "Comma-separated tags, optional (write).",
            ),
            "scope" to AgentToolParam(
                "string",
                "chat or global — chat is the default. Use global only for a fact about the user " +
                    "or this project that every future conversation should know.",
                enumValues = listOf("chat", "global"),
            ),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf(
            "tool_title", "action", "id", "title", "content", "query", "tags", "scope",
        ),
    )

    /** A tool result: the text the model sees, and whether the call worked. */
    data class ToolResult(val success: Boolean, val output: String)

    /**
     * Runs one `note` call.
     *
     * [scopeFor] resolves the caller's own scope: the chat session id in a
     * normal chat, the space id inside a Station panel. That single argument is
     * what keeps two panel agents from reading each other's notes — there is no
     * filtering afterwards, each call is simply given its own scope.
     */
    suspend fun execute(
        argsJson: String,
        context: Context,
        scopeFor: (global: Boolean) -> String,
    ): ToolResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolResult(false, "invalid arguments: ${it.message}")
        }
        if (!OzelFeatures.isEnabled(context, OzelFeatures.Feature.NOTES)) {
            return ToolResult(false, "notes are turned off in Settings › Minis Özel")
        }
        val store = NoteStore.get(context)
        val action = args.optString("action").trim().lowercase()
        val scope = if (args.optString("scope").equals("global", true)) {
            scopeFor(true)
        } else {
            scopeFor(false)
        }

        return when (action) {
            "list" -> listNotes(store, scope)
            "read" -> readNote(store, args.optString("id"))
            "write" -> writeNote(store, args, scope)
            "append" -> appendNote(store, args)
            "search" -> searchNotes(store, args.optString("query"))
            "delete" -> deleteNote(store, args.optString("id"))
            "vault_list" -> vaultList(context, store)
            "vault_read" -> vaultRead(context, store, args.optString("id"))
            "" -> ToolResult(false, "action is required")
            else -> ToolResult(false, "unknown action '$action'")
        }
    }

    // ── notes ───────────────────────────────────────────────────────────────

    private suspend fun listNotes(store: NoteStore, scope: String): ToolResult {
        val rows = store.visibleTo(scope).take(MAX_RESULTS)
        if (rows.isEmpty()) return ToolResult(true, "No notes yet. Use action=write to save one.")
        return ToolResult(true, buildString {
            append("${rows.size} note(s):\n")
            rows.forEach { n ->
                append("- [").append(n.id.take(8)).append("] ").append(n.title)
                if (n.scope == NoteStore.Scope.GLOBAL.wire) append(" (global)")
                val firstLine = n.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
                if (firstLine.isNotEmpty()) append(" — ").append(firstLine.take(120))
                append('\n')
            }
        })
    }

    private suspend fun readNote(store: NoteStore, rawId: String): ToolResult {
        val note = resolve(store, rawId) ?: return ToolResult(false, "no note with id '$rawId'")
        if (note.isVault) return ToolResult(false, "that id is a vault entry — use action=vault_read")
        return ToolResult(true, "# ${note.title}\n\n${note.body}")
    }

    private suspend fun writeNote(store: NoteStore, args: JSONObject, scope: String): ToolResult {
        val content = args.optString("content")
        val existing = resolve(store, args.optString("id"))
        val title = args.optString("title").ifBlank { existing?.title.orEmpty() }
        if (title.isBlank()) return ToolResult(false, "title is required for a new note")
        if (content.isBlank() && existing == null) return ToolResult(false, "content is required")
        val tags = args.optString("tags").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return store.write(
            scope = existing?.scope ?: scope,
            title = title,
            body = content,
            tags = tags,
            id = existing?.id,
        ).fold(
            onSuccess = { n ->
                ToolResult(true, "Saved note [${n.id.take(8)}] \"${n.title}\" (${n.body.length} chars).")
            },
            onFailure = { ToolResult(false, it.message ?: "could not save") },
        )
    }

    private suspend fun appendNote(store: NoteStore, args: JSONObject): ToolResult {
        val content = args.optString("content")
        if (content.isBlank()) return ToolResult(false, "content is required")
        val existing = resolve(store, args.optString("id"))
            ?: return ToolResult(false, "append needs the id of an existing note; use write to create one")
        if (existing.isVault) return ToolResult(false, "vault entries cannot be modified")
        val body = if (existing.body.isBlank()) content else existing.body + "\n" + content
        return store.write(
            scope = existing.scope,
            title = existing.title,
            body = body,
            tags = existing.tags,
            id = existing.id,
        ).fold(
            onSuccess = { ToolResult(true, "Appended to [${it.id.take(8)}] \"${it.title}\".") },
            onFailure = { ToolResult(false, it.message ?: "could not append") },
        )
    }

    private suspend fun searchNotes(store: NoteStore, query: String): ToolResult {
        if (query.isBlank()) return ToolResult(false, "query is required")
        // Notes only: a vault entry must not be discoverable by its content,
        // and its body is ciphertext at rest anyway.
        val rows = store.search(query, NoteStore.Kind.NOTE).take(MAX_RESULTS)
        if (rows.isEmpty()) return ToolResult(true, "No note matches \"$query\".")
        return ToolResult(true, buildString {
            append("${rows.size} match(es) for \"$query\":\n")
            rows.forEach { append("- [").append(it.id.take(8)).append("] ").append(it.title).append('\n') }
        })
    }

    private suspend fun deleteNote(store: NoteStore, rawId: String): ToolResult {
        val note = resolve(store, rawId) ?: return ToolResult(false, "no note with id '$rawId'")
        if (note.isVault) return ToolResult(false, "vault entries can only be deleted by the user")
        val ok = store.deleteNote(note.id)
        return if (ok) ToolResult(true, "Deleted \"${note.title}\".") else ToolResult(false, "could not delete")
    }

    // ── vault ───────────────────────────────────────────────────────────────

    private suspend fun vaultList(context: Context, store: NoteStore): ToolResult {
        if (!OzelFeatures.isEnabled(context, OzelFeatures.Feature.VAULT)) {
            return ToolResult(false, "the vault is turned off in Settings › Minis Özel")
        }
        val rows = store.vaultEntries()
        if (rows.isEmpty()) return ToolResult(true, "The vault is empty.")
        return ToolResult(true, buildString {
            append("${rows.size} vault entr(ies) — names only. ")
            append("Values need action=vault_read and the user's permission.\n")
            rows.forEach { append("- [").append(it.id.take(8)).append("] ").append(it.title).append('\n') }
        })
    }

    private suspend fun vaultRead(context: Context, store: NoteStore, rawId: String): ToolResult {
        if (!OzelFeatures.isEnabled(context, OzelFeatures.Feature.VAULT)) {
            return ToolResult(false, "the vault is turned off in Settings › Minis Özel")
        }
        if (!OzelFeatures.isEnabled(context, OzelFeatures.Feature.VAULT_AGENT_READ)) {
            return ToolResult(
                false,
                "The user has not allowed agents to read vault values. Ask them to enable " +
                    "Settings › Minis Özel › \"Agent may read the vault\", or to paste the value " +
                    "themselves.",
            )
        }
        val note = resolve(store, rawId) ?: return ToolResult(false, "no vault entry with id '$rawId'")
        if (!note.isVault) return ToolResult(false, "that id is a note — use action=read")
        val value = store.readVault(note.id)
            ?: return ToolResult(
                false,
                "The entry exists but cannot be decrypted on this device — its key was lost " +
                    "(device transfer or reinstall). The user has to enter it again.",
            )
        return ToolResult(true, value)
    }

    // ── id handling ─────────────────────────────────────────────────────────

    /**
     * Resolves a full id or the 8-character prefix the list prints. Prefix
     * matching is what makes the tool usable at all: asking a model to copy a
     * 36-character UUID verbatim is a reliable way to get a wrong id.
     */
    private fun resolve(store: NoteStore, rawId: String): NoteStore.Note? {
        val id = rawId.trim()
        if (id.isEmpty()) return null
        store.get(id)?.let { return it }
        return store.notes.value
            .filter { it.id.startsWith(id, ignoreCase = true) }
            .singleOrNull()
    }
}
