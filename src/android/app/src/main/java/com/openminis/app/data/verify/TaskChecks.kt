package com.openminis.app.data.verify

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [MINIS-OZEL 11.3.1] Definition of done: machine-checkable criteria a run has
 * to satisfy before it may claim it is finished.
 *
 * ## The failure this closes
 *
 * The most common complaint about an agent is not "it could not do it" — it is
 * "it said it was done and it was not". That is not a reasoning failure, it is a
 * missing step: nothing in the loop asked for evidence. A model that finishes by
 * writing "done" has no reason to re-open the file it just claimed to fix.
 *
 * A criterion is an assertion about the world, not a sentence: a file exists, a
 * file contains a string, a line matches a pattern, a JSON document has a key.
 * Those can be evaluated, so "done" becomes a measurement instead of an opinion.
 *
 * ## Why the checks are not shell commands
 *
 * A shell criterion would be the most expressive option and the wrong default:
 * it turns every verification into a code path that can itself fail, hang or
 * mutate the thing it is verifying, and it needs the sandbox up. The four
 * structured kinds cover what a file-editing run actually claims, and every one
 * of them is a pure function over bytes — testable, fast, and impossible to get
 * stuck on.
 *
 * ## Scope
 *
 * One criterion set per session, stored as JSON under `filesDir/verify/`. It is
 * scratch state, not user data: a new run starts with no criteria, and the set
 * is replaced when the agent declares a new one.
 */
object TaskChecks {

    private const val DIR = "verify"

    /** What a criterion asserts. */
    enum class Kind(val wire: String) {
        FILE_EXISTS("file_exists"),
        FILE_CONTAINS("file_contains"),
        FILE_MATCHES("file_matches"),
        JSON_HAS_KEY("json_has_key"),
        ;

        companion object {
            fun parse(raw: String?): Kind? = entries.firstOrNull { it.wire == raw?.trim() }
        }
    }

    data class Check(
        val id: String,
        val kind: Kind,
        val path: String,
        /** The needle, pattern or key; empty for FILE_EXISTS. */
        val expectation: String,
        val note: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("kind", kind.wire)
            put("path", path)
            put("expect", expectation)
            put("note", note)
        }

        companion object {
            fun fromJson(o: JSONObject): Check? {
                val kind = Kind.parse(o.optString("kind")) ?: return null
                return Check(
                    id = o.optString("id").ifEmpty { UUID.randomUUID().toString().take(8) },
                    kind = kind,
                    path = o.optString("path"),
                    expectation = o.optString("expect"),
                    note = o.optString("note"),
                )
            }
        }
    }

    data class Outcome(val check: Check, val passed: Boolean, val detail: String)

    data class Report(val outcomes: List<Outcome>) {
        val allPassed: Boolean get() = outcomes.isNotEmpty() && outcomes.all { it.passed }
        val failed: List<Outcome> get() = outcomes.filter { !it.passed }

        /** Human/agent-readable, and deliberately blunt about what failed. */
        fun render(): String {
            if (outcomes.isEmpty()) return "No criteria declared — nothing was verified."
            val head = if (allPassed) {
                "All ${outcomes.size} criterion/criteria passed."
            } else {
                "${failed.size} of ${outcomes.size} criterion/criteria FAILED."
            }
            return buildString {
                appendLine(head)
                outcomes.forEach { o ->
                    val expect = if (o.check.expectation.isNotEmpty()) {
                        " :: ${o.check.expectation}"
                    } else {
                        ""
                    }
                    val note = if (o.check.note.isNotEmpty()) " (${o.check.note})" else ""
                    val detail = if (o.detail.isNotEmpty()) " — ${o.detail}" else ""
                    val mark = if (o.passed) "PASS" else "FAIL"
                    appendLine("- $mark ${o.check.kind.wire} ${o.check.path}$expect$note$detail")
                }
            }.trimEnd()
        }
    }

    // ── storage ─────────────────────────────────────────────────────────────

    /** Test seam, same reason and shape as ActionJournal.testRoot. */
    internal var testRoot: File? = null

    private fun dir(ctx: android.content.Context?): File {
        val d = testRoot ?: File(
            requireNotNull(ctx) { "TaskChecks needs a Context when testRoot is unset" }
                .applicationContext.filesDir,
            DIR,
        )
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun file(ctx: android.content.Context?, sessionId: String): File =
        File(dir(ctx), "${sessionId.ifBlank { "global" }.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    fun load(ctx: android.content.Context?, sessionId: String): List<Check> {
        val f = file(ctx, sessionId)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                Check.fromJson(arr.optJSONObject(i) ?: return@mapNotNull null)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: android.content.Context?, sessionId: String, checks: List<Check>) {
        val arr = JSONArray()
        checks.forEach { arr.put(it.toJson()) }
        file(ctx, sessionId).writeText(arr.toString())
    }

    fun clear(ctx: android.content.Context?, sessionId: String) {
        file(ctx, sessionId).delete()
    }

    // ── evaluation ──────────────────────────────────────────────────────────

    /**
     * Evaluates one criterion by reading [path] as a file.
     *
     * Pure apart from the file read, so the rules are unit-testable. Every
     * failure returns a specific [detail]: "the file is not there" and "the file
     * is there but does not contain the string" are different problems with
     * different next steps, and collapsing them into one boolean wastes the
     * agent's turn.
     */
    fun evaluate(check: Check, root: File? = null): Outcome {
        val target = if (root != null && !check.path.startsWith("/")) {
            File(root, check.path)
        } else {
            File(check.path)
        }

        if (check.kind == Kind.FILE_EXISTS) {
            return when {
                !target.exists() -> Outcome(check, false, "no such file")
                target.length() == 0L -> Outcome(check, false, "file exists but is empty")
                else -> Outcome(check, true, "${target.length()} B")
            }
        }

        if (!target.exists()) return Outcome(check, false, "no such file")
        val text = try {
            target.readText()
        } catch (e: Exception) {
            return Outcome(check, false, "unreadable: ${e.javaClass.simpleName}")
        }

        return when (check.kind) {
            Kind.FILE_EXISTS -> Outcome(check, true, "")   // handled above
            Kind.FILE_CONTAINS -> {
                if (check.expectation.isEmpty()) {
                    Outcome(check, false, "criterion has no text to look for")
                } else if (text.contains(check.expectation)) {
                    Outcome(check, true, "found")
                } else {
                    Outcome(check, false, "not found in ${text.length} chars")
                }
            }

            Kind.FILE_MATCHES -> {
                if (check.expectation.isEmpty()) {
                    Outcome(check, false, "criterion has no pattern")
                } else {
                    try {
                        if (Regex(check.expectation, RegexOption.MULTILINE).containsMatchIn(text)) {
                            Outcome(check, true, "matched")
                        } else {
                            Outcome(check, false, "no line matched the pattern")
                        }
                    } catch (e: Exception) {
                        Outcome(check, false, "invalid pattern: ${e.message ?: "?"}")
                    }
                }
            }

            Kind.JSON_HAS_KEY -> {
                try {
                    val obj = JSONObject(text)
                    val key = check.expectation
                    if (key.isEmpty()) {
                        Outcome(check, false, "criterion has no key")
                    } else if (obj.has(key)) {
                        Outcome(check, true, "key present")
                    } else {
                        Outcome(check, false, "JSON parses but has no key '$key'")
                    }
                } catch (e: Exception) {
                    Outcome(check, false, "not valid JSON")
                }
            }
        }
    }

    fun runAll(checks: List<Check>, root: File? = null): Report =
        Report(checks.map { evaluate(it, root) })
}
