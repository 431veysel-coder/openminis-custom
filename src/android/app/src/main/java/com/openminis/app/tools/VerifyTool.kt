package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.OzelFeatures
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.verify.TaskChecks
import org.json.JSONObject

/**
 * [MINIS-OZEL 11.3.1] `verify` — make "done" a measurement, not a claim.
 *
 * ## Why this tool exists
 *
 * The most common complaint about an agent is not "it could not do it". It is
 * "it said it was done and it was not" — because nothing in the loop ever asked
 * for evidence. This gives the model a place to write down what finished will
 * look like, and then a way to check it before saying so.
 *
 * The intended rhythm is three calls, not one:
 *
 *   1. `set` before starting — the criteria the result must satisfy;
 *   2. `run` while working — see which ones still fail;
 *   3. `run` before the final answer — and if something fails, keep working
 *      instead of reporting success.
 *
 * ## The failure that is explicitly allowed
 *
 * A run may end with criteria unset, or with failing ones, if it SAYS so.
 * Refusing to finish would be worse: sometimes the honest outcome is "I could
 * not do X, here is what is left". What is not allowed is claiming completion
 * with a failing check on record, and the report makes that visible in the
 * transcript rather than leaving it to the reader to notice.
 */
object VerifyTool {

    const val NAME = "verify"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Define and check the criteria for 'done' before you claim a task is " +
            "finished. " +
            "Actions: " +
            "set (replace the criteria: pass `checks`, an array of objects with kind, path, " +
            "expect and optional note), " +
            "run (evaluate every criterion and report PASS/FAIL per item), " +
            "status (show the criteria without evaluating), " +
            "clear (remove them). " +
            "Kinds: file_exists (expect can be empty), file_contains, file_matches (regular " +
            "expression, multiline), json_has_key. " +
            "Use it when a task has a verifiable result: tests written, a config containing a " +
            "value, a document with a section, a JSON file with a key. " +
            "Before your final answer, call run: if anything fails, keep working or report " +
            "plainly what is left. Do not claim completion with a failing check on record.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does, shown to the user " +
                    "(e.g. 'Check the acceptance criteria'). Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "What to do",
                enumValues = listOf("set", "run", "status", "clear"),
            ),
            "checks" to AgentToolParam(
                "array",
                "For action=set: the criteria. Each item: " +
                    "{ \"kind\": \"file_exists|file_contains|file_matches|json_has_key\", " +
                    "\"path\": \"/absolute/path\", \"expect\": \"needle|regex|key\", " +
                    "\"note\": \"why this matters\" }",
            ),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "checks"),
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
        val action = args.optString("action", "status").trim().lowercase(java.util.Locale.ROOT)

        return when (action) {
            "set" -> {
                val arr = args.optJSONArray("checks")
                    ?: return ToolExecutionResult(
                        "Error: action=set needs `checks` (an array). " +
                            "Example: [{\"kind\":\"file_exists\",\"path\":\"/tmp/a.txt\"}]",
                        false,
                    )
                val checks = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val kind = TaskChecks.Kind.parse(o.optString("kind"))
                        ?: return@mapNotNull null
                    val path = o.optString("path")
                    if (path.isBlank()) return@mapNotNull null
                    TaskChecks.Check(
                        id = (i + 1).toString(),
                        kind = kind,
                        path = path,
                        expectation = o.optString("expect"),
                        note = o.optString("note"),
                    )
                }
                if (checks.isEmpty()) {
                    return ToolExecutionResult(
                        "Error: no usable criterion in `checks`. Each needs a valid " +
                            "'kind' (${TaskChecks.Kind.entries.joinToString(", ") { it.wire }}) " +
                            "and a 'path'.",
                        false,
                    )
                }
                TaskChecks.save(context, sessionId, checks)
                ToolExecutionResult(
                    "Recorded ${checks.size} criterion/criteria:\n" +
                        checks.joinToString("\n") { "- ${it.kind.wire} ${it.path}" } +
                        "\nRun them with action=run before your final answer.",
                    true,
                )
            }

            "run" -> {
                val checks = TaskChecks.load(context, sessionId)
                if (checks.isEmpty()) {
                    return ToolExecutionResult(
                        "No criteria recorded. Use action=set first, or finish and say plainly " +
                            "that the result was not verified.",
                        false,
                    )
                }
                val report = TaskChecks.runAll(checks)
                ToolExecutionResult(report.render(), report.allPassed)
            }

            "status" -> {
                val checks = TaskChecks.load(context, sessionId)
                if (checks.isEmpty()) {
                    ToolExecutionResult("No criteria recorded.", true)
                } else {
                    ToolExecutionResult(
                        checks.joinToString("\n") { "- [${it.id}] ${it.kind.wire} ${it.path}" +
                            (if (it.expectation.isNotEmpty()) " :: ${it.expectation}" else "") },
                        true,
                    )
                }
            }

            "clear" -> {
                TaskChecks.clear(context, sessionId)
                ToolExecutionResult("Criteria cleared.", true)
            }

            else -> ToolExecutionResult(
                "Error: unknown action '$action' (set, run, status, clear)",
                false,
            )
        }
    }

    fun enabled(context: Context): Boolean =
        OzelFeatures.isEnabled(context, OzelFeatures.Feature.ACCEPTANCE_CRITERIA)
}
