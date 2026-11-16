package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.OzelFeatures
import com.openminis.app.data.journal.ActionJournal
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * [MINIS-OZEL 11.3.1] `journal` — the agent's memory of what it changed.
 *
 * ## Why the agent needs to read its own journal
 *
 * Two failure modes this closes, both seen in this project:
 *
 * 1. **Lost context.** After a compaction or a long run, the model no longer
 *    knows what it edited twenty steps ago, so it re-reads files and "fixes"
 *    things that were already fixed. The journal answers "what did I already
 *    do" from disk instead of from the context window.
 * 2. **No retreat.** A model that notices a bad edit can now undo it and try
 *    again, instead of stacking a second edit on top of the first. A stack of
 *    compensating edits is how a small mistake becomes nine blind patches.
 *
 * ## What it deliberately cannot do
 *
 * It cannot undo an arbitrary entry by target path, only by id — the model must
 * name the change it means, and the id came from a real write. It cannot undo
 * an entry that is already undone. It cannot see entries from another chat
 * session, so a sub agent cannot quietly reverse the main run's work.
 *
 * Entries are listed newest-first and never block: everything here is small
 * file reads, so no dispatcher hop is needed and the tool stays fast enough to
 * be called reflexively.
 */
object JournalTool {

    const val NAME = "journal"

    private const val MAX_RESULTS = 30

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "The record of every file change made in this session, with the " +
            "recipe to undo each one. Use it when you are unsure what you already changed, " +
            "before re-reading a file you may have edited, and to reverse a change you " +
            "regret instead of stacking a second edit on top of it. " +
            "Actions: list (newest first, this session plus global changes), " +
            "read (full detail of one entry, by id), " +
            "undo (reverse one change by id). " +
            "Undo refuses rather than guesses: if the file changed since, it says so and " +
            "leaves it alone. A change with undo kind 'none' cannot be reversed and says why.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does, shown to the user " +
                    "(e.g. 'Check what I already edited'). Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "What to do",
                enumValues = listOf("list", "read", "undo"),
            ),
            "id" to AgentToolParam(
                "string",
                "Entry id (read / undo). The 8-character prefix shown in the list is enough.",
            ),
            "limit" to AgentToolParam(
                "integer",
                "Maximum entries to list (default 20, max $MAX_RESULTS)",
            ),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "id", "limit"),
    )

    fun execute(
        argsJson: String,
        context: Context,
        sessionId: String,
    ): ToolExecutionResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: Exception) {
            return ToolExecutionResult("Error: could not parse arguments", false)
        }
        val action = args.optString("action", "list").trim().lowercase(java.util.Locale.ROOT)

        return when (action) {
            "list" -> {
                val limit = args.optInt("limit", 20).coerceIn(1, MAX_RESULTS)
                // This session AND global entries: a sub agent's writes belong
                // to the session it ran in, so filtering purely by sessionId
                // would hide exactly the changes a user is asking about when a
                // delegated run went wrong.
                val entries = ActionJournal.recent(context, limit = limit * 2)
                    .filter { it.sessionId == null || it.sessionId == sessionId }
                    .take(limit)
                ToolExecutionResult(ActionJournal.summarize(entries), true)
            }

            "read" -> {
                val id = args.optString("id", "").trim()
                if (id.isEmpty()) return ToolExecutionResult("Error: 'id' is required", false)
                val entry = ActionJournal.find(context, id)
                    ?: return ToolExecutionResult("Error: no journal entry '$id'", false)
                if (entry.sessionId != null && entry.sessionId != sessionId) {
                    return ToolExecutionResult(
                        "Error: entry '$id' belongs to another session and cannot be inspected here.",
                        false,
                    )
                }
                val detail = buildString {
                    appendLine("id: ${entry.id}")
                    appendLine("when: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(entry.timestamp))}")
                    appendLine("tool: ${entry.tool}")
                    appendLine("target: ${entry.target}")
                    if (entry.title.isNotEmpty()) appendLine("title: ${entry.title}")
                    appendLine("undo: ${entry.undoKind.wire}${if (entry.undone) " (already undone)" else ""}")
                    appendLine("size: ${entry.beforeBytes} B before, ${entry.afterBytes} B after")
                    if (entry.note.isNotEmpty()) appendLine("note: ${entry.note}")
                }
                ToolExecutionResult(detail.trimEnd(), true)
            }

            "undo" -> {
                val id = args.optString("id", "").trim()
                if (id.isEmpty()) return ToolExecutionResult("Error: 'id' is required", false)
                val entry = ActionJournal.find(context, id)
                    ?: return ToolExecutionResult("Error: no journal entry '$id'", false)
                if (entry.sessionId != null && entry.sessionId != sessionId) {
                    return ToolExecutionResult(
                        "Error: entry '$id' belongs to another session; only the session that " +
                            "made a change can undo it.",
                        false,
                    )
                }
                val result = ActionJournal.undo(context, entry)
                ToolExecutionResult(result.message, result.ok)
            }

            else -> ToolExecutionResult(
                "Error: unknown action '$action' (list, read, undo)",
                false,
            )
        }
    }

    /** Whether the tool should be offered at all. */
    fun enabled(context: Context): Boolean =
        OzelFeatures.isEnabled(context, OzelFeatures.Feature.JOURNAL)
}
