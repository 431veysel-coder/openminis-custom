package com.openminis.app.tools

import com.openminis.app.data.repository.NoteRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool schema definitions for agent note operations.
 * Mirrors the iOS agent tool registration pattern.
 */
object NoteTool {

    const val NAME = "note"

    val description = """Manage persistent per-session notes for the agent. Notes survive across conversations in the same session. Actions: create, read, update, delete, list, search.""".trimIndent()

    /** Build the JSON tool definition for Anthropic / OpenAI / Gemini API. */
    fun toolDefinition(): JSONObject {
        val tool = JSONObject()
        tool.put("name", NAME)
        tool.put("description", description)

        val properties = JSONObject()

        properties.put("tool_title", JSONObject().apply {
            put("type", "string")
            put("description", "5-10 word summary of what this call does")
        })

        properties.put("action", JSONObject().apply {
            put("type", "string")
            put("description", "The note action to perform")
            put("enum", JSONArray(listOf("create", "read", "update", "delete", "list", "search")))
        })

        properties.put("session_id", JSONObject().apply {
            put("type", "string")
            put("description", "Session ID (required for all actions except list without session filter)")
        })

        properties.put("id", JSONObject().apply {
            put("type", "string")
            put("description", "Note ID (required for read, update, delete)")
        })

        properties.put("title", JSONObject().apply {
            put("type", "string")
            put("description", "Note title (for create, update)")
        })

        properties.put("content", JSONObject().apply {
            put("type", "string")
            put("description", "Note content (for create, update)")
        })

        properties.put("tags", JSONObject().apply {
            put("type", "string")
            put("description", "Comma-separated tags (for create, update)")
        })

        properties.put("query", JSONObject().apply {
            put("type", "string")
            put("description", "Search query (for search action)")
        })

        properties.put("limit", JSONObject().apply {
            put("type", "integer")
            put("description", "Max results for list/search (default 50)")
            put("default", 50)
        })

        val inputSchema = JSONObject()
        inputSchema.put("type", "object")
        inputSchema.put("properties", properties)
        inputSchema.put("required", JSONArray(listOf("tool_title", "action")))

        tool.put("input_schema", inputSchema)
        return tool
    }

    /** Anthropic API format. */
    fun anthropicToolDefinition(): JSONObject = toolDefinition()

    /** OpenAI function calling format. */
    fun openAIFunctionDefinition(): JSONObject {
        val def = toolDefinition()
        val fn = JSONObject()
        fn.put("type", "function")
        val function = JSONObject()
        function.put("name", def.getString("name"))
        function.put("description", def.getString("description"))
        function.put("parameters", def.getJSONObject("input_schema"))
        fn.put("function", function)
        return fn
    }

    /**
     * Execute a note action. Returns a JSON result object.
     * This is called by the offload handler when the model invokes the tool.
     */
    suspend fun execute(
        repository: NoteRepository,
        sessionId: String,
        action: String,
        id: String? = null,
        title: String? = null,
        content: String? = null,
        tags: String? = null,
        query: String? = null,
        limit: Int = 50,
    ): JSONObject {
        return when (action) {
            "create" -> {
                val note = repository.create(sessionId, title.orEmpty(), content.orEmpty(), tags.orEmpty())
                if (note == null) {
                    JSONObject().put("error", "session_id is required")
                } else {
                    JSONObject().apply {
                        put("success", true)
                        put("note", noteToJson(note))
                    }
                }
            }
            "read" -> {
                if (id.isNullOrBlank()) {
                    JSONObject().put("error", "id is required for read")
                } else {
                    val note = repository.get(id)
                    if (note == null) {
                        JSONObject().put("error", "Note not found: $id")
                    } else {
                        JSONObject().apply {
                            put("success", true)
                            put("note", noteToJson(note))
                        }
                    }
                }
            }
            "update" -> {
                if (id.isNullOrBlank()) {
                    JSONObject().put("error", "id is required for update")
                } else {
                    val ok = repository.update(id, title, content, tags)
                    if (!ok) {
                        JSONObject().put("error", "Note not found: $id")
                    } else {
                        val note = repository.get(id)
                        JSONObject().apply {
                            put("success", true)
                            put("note", noteToJson(note!!))
                        }
                    }
                }
            }
            "delete" -> {
                if (id.isNullOrBlank()) {
                    JSONObject().put("error", "id is required for delete")
                } else {
                    val existed = repository.get(id) != null
                    repository.delete(id)
                    JSONObject().apply {
                        put("success", true)
                        put("deleted", existed)
                    }
                }
            }
            "list" -> {
                val notes = repository.listForSession(sessionId).take(limit)
                JSONObject().apply {
                    put("success", true)
                    put("count", notes.size)
                    put("notes", JSONArray(notes.map { noteToJson(it) }))
                }
            }
            "search" -> {
                val q = query.orEmpty()
                if (q.isBlank()) {
                    JSONObject().put("error", "query is required for search")
                } else {
                    val notes = repository.search(sessionId, q).take(limit)
                    JSONObject().apply {
                        put("success", true)
                        put("count", notes.size)
                        put("notes", JSONArray(notes.map { noteToJson(it) }))
                    }
                }
            }
            else -> JSONObject().put("error", "Unknown action: $action")
        }
    }

    private fun noteToJson(note: NoteRepository.Note): JSONObject = JSONObject().apply {
        put("id", note.id)
        put("session_id", note.sessionId)
        put("title", note.title)
        put("content", note.content)
        put("tags", note.tags)
        put("version", note.version)
        put("created_at", note.createdAt)
        put("updated_at", note.updatedAt)
        put("preview", note.preview)
    }
}