package com.openminis.app.ui.settings

import com.openminis.app.data.db.MessageEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * [MINIS-OZEL] One tool call the model made, with whatever came back.
 *
 * This is a *read model* over the transcript, not a new store: the agent loop
 * already writes every toolUse/toolResult into `messages.parts_json`, so the
 * panel shows exactly what happened without the loop knowing the panel exists.
 * Nothing here can invent a step the model did not take.
 */
data class AgentStep(
    val toolUseId: String,
    val name: String,
    /** The one-line intent the model gave the call ("Reading the config"). */
    val description: String?,
    /** Raw arguments, JSON or plain text. */
    val input: String,
    /** Null while the call is still in flight — this is the live signal. */
    val output: String?,
    /** Null until the result lands. */
    val success: Boolean?,
    /** When the assistant message carrying this call was written. */
    val atMillis: Long,
) {
    val running: Boolean get() = output == null
}

/**
 * Flattens transcript rows into an ordered step list.
 *
 * Rules that matter:
 * - A `toolUse` with no matching `toolResult` is *running* — that is how the
 *   panel knows a turn is mid-flight rather than guessing from timers.
 * - Results are matched by `toolUseId`, never by position: parallel calls come
 *   back in a different order than they were issued.
 * - Order follows the transcript (sort_order), so the list reads top-down like
 *   the chat does.
 */
object AgentStepExtractor {

    /** Cap per field so one huge `shell_execute` output cannot blow up a row. */
    private const val FIELD_CAP = 4_000

    fun extract(messages: List<MessageEntity>, maxSteps: Int = 40): List<AgentStep> {
        val uses = LinkedHashMap<String, AgentStep>()
        val results = HashMap<String, Pair<String, Boolean>>()

        for (msg in messages) {
            // Only assistant turns carry toolUse parts; skipping the rest keeps
            // the JSON parse count proportional to what actually ran.
            val parts = runCatching { JSONArray(msg.partsJson) }.getOrNull() ?: continue
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "toolUse" -> {
                        val v = part.optJSONObject("value") ?: continue
                        val id = v.optString("toolUseId")
                        if (id.isEmpty()) continue
                        uses[id] = AgentStep(
                            toolUseId = id,
                            name = v.optString("name").ifBlank { "tool" },
                            description = v.optString("description").takeIf { it.isNotBlank() },
                            input = v.optString("input").take(FIELD_CAP),
                            output = null,
                            success = null,
                            atMillis = msg.createdAt,
                        )
                    }
                    "toolResult" -> {
                        val v = part.optJSONObject("value") ?: continue
                        val id = v.optString("toolUseId")
                        if (id.isEmpty()) continue
                        results[id] = v.optString("output").take(FIELD_CAP) to
                            v.optBoolean("success", true)
                    }
                }
            }
        }

        // Rebuild in issue order, attaching each result.
        val ordered = uses.values.map { step ->
            val r = results[step.toolUseId]
            if (r == null) step else step.copy(output = r.first, success = r.second)
        }
        return if (ordered.size <= maxSteps) ordered else ordered.takeLast(maxSteps)
    }

    /**
     * The shortest honest summary of a call: the argument the model most likely
     * meant as the subject. Falls back to the raw first line.
     */
    fun headline(input: String): String {
        if (input.isBlank()) return ""
        val row = runCatching { JSONObject(input) }.getOrNull()
        if (row != null) {
            for (key in HEADLINE_KEYS) {
                val v = row.optString(key)
                if (v.isNotBlank()) return v.lineSequence().first().take(140)
            }
            // No known key: show the first scalar value so the row is still
            // informative rather than dumping JSON braces.
            val keys = row.keys()
            while (keys.hasNext()) {
                val v = row.optString(keys.next())
                if (v.isNotBlank() && !v.startsWith("{") && !v.startsWith("[")) {
                    return v.lineSequence().first().take(140)
                }
            }
        }
        return input.lineSequence().first().take(140)
    }

    /** "2.4s" style, for a finished call. */
    fun durationLabel(startAtMillis: Long, nowMillis: Long): String {
        val secs = ((nowMillis - startAtMillis).coerceAtLeast(0L)) / 1000.0
        return if (secs < 60) String.format(java.util.Locale.US, "%.1fs", secs)
        else String.format(java.util.Locale.US, "%dm %02ds", (secs / 60).toInt(), (secs % 60).toInt())
    }

    /** Keys that name the subject of a call, most specific first. */
    private val HEADLINE_KEYS = listOf(
        "command", "query", "url", "path", "file_path", "pattern",
        "prompt", "text", "title", "name", "action", "goal", "task",
    )
}
