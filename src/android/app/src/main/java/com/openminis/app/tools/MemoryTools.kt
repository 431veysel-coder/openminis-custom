package com.openminis.app.tools

import com.openminis.app.data.repository.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool definitions and execution for memory_write and memory_get.
 * Schema matches iOS AgentToolDefinition exactly.
 */
object MemoryTools {

    // -- Tool Definitions (Anthropic format) --

    fun memoryWriteToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", JSONObject().apply {
                put("type", "string")
                put("description", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user.")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned.")
            })
            put("scope", JSONObject().apply {
                put("type", "string")
                put("description", "Which memory to write to. 'project' (default inside a project): facts that only matter for this project — decisions, file layout, conventions. 'global': things that outlive any project — the user's language, their name, cross-project preferences. Outside a project both are the same personal log.")
                put("enum", JSONArray().apply {
                    put("project")
                    put("global")
                })
            })
        }

        return JSONObject().apply {
            put("name", "memory_write")
            put("description", "Write a memory entry to today's daily log (YYYY-MM-DD.md) of the current memory scope. Inside a project the entry goes to the PROJECT's log by default and is only visible to that project's chats; pass scope='global' for a fact that should follow the user everywhere. Outside a project everything goes to the personal GLOBAL log. Memories persist across sessions. Each entry is prepended with a timestamp. Save: user preferences, recurring patterns, key facts, project conventions, reusable knowledge. Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned. GLOBAL.md / PROJECT.md are writable but not through this tool; update them when the user asks.")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray().apply {
                    put("tool_title")
                    put("content")
                })
            })
        }
    }

    fun memoryGetToolDefinition(): JSONObject {
        val properties = JSONObject().apply {
            put("tool_title", JSONObject().apply {
                put("type", "string")
                put("description", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user.")
            })
            put("scope", JSONObject().apply {
                put("type", "string")
                put("description", "Memory scope to search: 'all' (default — this project's memory AND the user's personal memory, and both GLOBAL.md files), 'project' (this project's PROJECT.md + daily logs only), 'global' (the user's personal GLOBAL.md + daily logs only). Outside a project, 'project' and 'global' both mean the personal memory.")
                put("enum", JSONArray().apply {
                    put("all")
                    put("project")
                    put("global")
                    put("daily")
                })
            })
            put("keywords", JSONObject().apply {
                put("type", "string")
                put("description", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files.")
            })
        }

        return JSONObject().apply {
            put("name", "memory_get")
            put("description", "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray().apply {
                    put("tool_title")
                })
            })
        }
    }

    // -- OpenAI Function Calling format --

    fun memoryWriteOpenAIDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "memory_write")
                put("description", memoryWriteToolDefinition().getString("description"))
                put("parameters", memoryWriteToolDefinition().getJSONObject("input_schema"))
            })
        }
    }

    fun memoryGetOpenAIDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "memory_get")
                put("description", memoryGetToolDefinition().getString("description"))
                put("parameters", memoryGetToolDefinition().getJSONObject("input_schema"))
            })
        }
    }

    // -- Execution --

    data class ToolResult(
        val output: String,
        val success: Boolean,
        val toolTitle: String = "",
    )

    fun executeMemoryWrite(inputJson: String, repository: MemoryRepository): ToolResult =
        executeMemoryWrite(inputJson, personal = repository, project = null, projectName = null)

    /**
     * [MINIS-OZEL 37074] Scope-aware write.
     *
     * Routing, and why it is this way round:
     *  - in a project the DEFAULT target is the project: that is the layer the
     *    user asked to exist, and a decision about this repository has no
     *    business in the personal log every other project reads;
     *  - `scope=global` is the explicit escape hatch, and it is a DECLARATION
     *    the model has to make, so a misfiled note is a visible choice rather
     *    than an accident;
     *  - with no project both collapse to personal — pre-37070 behaviour.
     *
     * The success line names the destination (`[proje belleği: X]` /
     * `[kişisel bellek]`) so the user reading the transcript can see where the
     * note went without opening anything.
     */
    fun executeMemoryWrite(
        inputJson: String,
        personal: MemoryRepository,
        project: MemoryRepository?,
        projectName: String?,
    ): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val content = obj.optString("content", "")
            val toolTitle = obj.optString("tool_title", "memory_write")
            val wantsGlobal = obj.optString("scope", "").equals("global", ignoreCase = true)

            if (content.isBlank()) {
                return ToolResult("Error: Missing required 'content' parameter", false, toolTitle)
            }
            val target = if (wantsGlobal || project == null) personal else project
            val where = when {
                project == null -> ""
                wantsGlobal -> " [kişisel bellek]"
                else -> " [proje belleği: ${projectName ?: "?"}]"
            }
            val result = target.writeMemory(content)
            val success = result.startsWith("Memory saved")
            ToolResult(if (success) result + where else result, success, toolTitle)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }

    fun executeMemoryGet(inputJson: String, repository: MemoryRepository): ToolResult =
        executeMemoryGet(inputJson, personal = repository, project = null, projectName = null)

    /**
     * [MINIS-OZEL 37074] Scope-aware read.
     *
     * `all` is the default and searches BOTH layers, project first: the project
     * layer is the smaller, more specific one, so its hits are the ones the
     * model usually wants, and putting it first also means the 30 KB output cap
     * trims the general stuff rather than the specific stuff.
     *
     * Each layer's output is prefixed with a one-line header and separated by a
     * blank line, so a hit can be attributed to the right log. Without that the
     * model would answer "we decided X" without knowing whether X was decided
     * for THIS project or for the user in general.
     */
    fun executeMemoryGet(
        inputJson: String,
        personal: MemoryRepository,
        project: MemoryRepository?,
        projectName: String?,
    ): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val keywords = obj.optString("keywords", "")
            val scope = obj.optString("scope", "all").lowercase()
            val toolTitle = obj.optString("tool_title", "memory_get")

            if (project == null) {
                // No project: every scope name means the personal layer, which is
                // exactly what the pre-37070 tool did with 'all' / 'daily'.
                val personalScope = if (scope == "global" || scope == "all") "all" else scope
                return ToolResult(personal.getMemory(keywords, personalScope), true, toolTitle)
            }

            val parts = mutableListOf<String>()
            when (scope) {
                "project" -> parts += MemoryRepository.projectFragmentHeader(projectName) +
                    project.getMemory(keywords, "all")
                "global" -> parts += MemoryRepository.personalFragmentHeader() +
                    personal.getMemory(keywords, "all")
                else -> {
                    parts += MemoryRepository.projectFragmentHeader(projectName) +
                        project.getMemory(keywords, "all")
                    parts += MemoryRepository.personalFragmentHeader() +
                        personal.getMemory(keywords, "all")
                }
            }
            ToolResult(parts.joinToString("\n\n"), true, toolTitle)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }
}
