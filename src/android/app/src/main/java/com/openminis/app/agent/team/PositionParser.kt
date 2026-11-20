package com.openminis.app.agent.team

import org.json.JSONArray
import org.json.JSONObject

/**
 * [MINIS-OZEL 11.5 · 37037] Reads a member's position out of whatever the model
 * actually sent.
 *
 * ## Why this is tolerant instead of strict
 *
 * A council's members are five different models from five different providers.
 * Every one of them can wrap the JSON in a fenced block, prefix it with "Here
 * is my position:", escape a quote wrong, or emit `'` instead of `"`. A strict
 * parser would make the cheapest models unusable and would fail a whole round
 * over a formatting habit — while the content was perfectly good.
 *
 * The opposite failure is worse, though, and this parser is shaped by it: a
 * tolerant parser that INVENTS structure turns a member's silence into an
 * opinion. So the tolerance is bounded — recoverable sloppiness is repaired,
 * missing substance is never filled in. If there is no claim sentence, the
 * position comes back `absent` with the reason, and the math sees a member who
 * did not vote rather than a member who voted "uncertain".
 */
object PositionParser {

    /** Points beyond this are dropped: a 40-point position is an essay, not a position. */
    const val MAX_POINTS = 12
    const val MAX_QUESTIONS = 3
    /** [37051] Tasks one position may hand out. Past three it is a plan, not a hand-over. */
    const val MAX_TASKS = 3
    const val MAX_TEXT = 600
    const val MAX_CLAIM = 400

    fun parse(raw: String?, member: String): Position {
        if (raw.isNullOrBlank()) return absent(member, "boş cevap")
        val json = extractJsonObject(raw) ?: return absent(member, "JSON bulunamadı")

        val obj = runCatching { JSONObject(json) }.getOrNull()
            ?: return absent(member, "JSON okunamadı")

        val claim = obj.optString("claim").trim().take(MAX_CLAIM)
        val points = parsePoints(obj.optJSONArray("points"))
        val falsifier = obj.optString("falsifier").trim().take(MAX_TEXT)

        // A position with no claim and no points is not a position. It is a
        // model answering a different question, and counting it as UNCERTAIN
        // everything would quietly manufacture a stalemate.
        if (claim.isEmpty() && points.isEmpty()) {
            return absent(member, "claim ve points ikisi de boş")
        }

        return Position(
            member = member,
            claim = claim,
            points = points,
            changes = parseChanges(obj.optJSONArray("changes")),
            questions = parseQuestions(obj.optJSONArray("questions")),
            tasks = parseTasks(obj.optJSONArray("tasks")),
            falsifier = falsifier,
        )
    }

    private fun absent(member: String, why: String) =
        Position(member = member, claim = "", absent = true, absentWhy = why)

    private fun parsePoints(arr: JSONArray?): List<Point> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            // A point may be written as a bare string. That carries no stance,
            // so it is read as the member agreeing with its own claim — the
            // only reading that does not put words in its mouth.
            val raw = arr.opt(i)
            val obj = raw as? JSONObject
            if (obj == null) {
                val text = raw?.toString()?.trim().orEmpty().take(MAX_TEXT)
                if (text.isEmpty()) return@mapNotNull null
                return@mapNotNull Point(ref = "p${i + 1}", text = text, stance = Stance.SUPPORT)
            }
            val text = obj.optString("text").trim().take(MAX_TEXT)
            if (text.isEmpty()) return@mapNotNull null
            Point(
                ref = obj.optString("ref").trim().ifBlank { "p${i + 1}" }.take(16),
                text = text,
                stance = Stance.parse(obj.optString("stance")),
                basis = Basis.parse(obj.optString("basis")),
                evidence = stringList(obj.opt("evidence")),
            )
        }.take(MAX_POINTS)
    }

    private fun parseChanges(arr: JSONArray?): List<Change> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val ref = obj.optString("ref").trim().take(16)
            if (ref.isEmpty()) return@mapNotNull null
            Change(
                ref = ref,
                from = Stance.parse(obj.optString("from")),
                to = Stance.parse(obj.optString("to")),
                why = obj.optString("why").trim().take(MAX_TEXT),
            )
        }.take(MAX_POINTS)
    }

    private fun parseQuestions(arr: JSONArray?): List<Question> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val to = obj.optString("to").trim().take(60)
            val q = obj.optString("q").trim().take(MAX_TEXT)
            if (to.isEmpty() || q.isEmpty()) return@mapNotNull null
            Question(to, q)
        }.take(MAX_QUESTIONS)
    }

    /**
     * [37051] Work handed to a colleague.
     *
     * A task needs a target and a title; `why` and `expect` are optional but are
     * what make the task actionable, so they are kept when present. Rows without a
     * target are DROPPED rather than assigned to whoever is first — the same rule
     * the claim matrix follows, for the same reason: an invented recipient is
     * invisible in the report, a missing one is not.
     */
    private fun parseTasks(arr: JSONArray?): List<TaskRequest> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val to = obj.optString("to").trim().take(60)
            val title = obj.optString("title").trim().take(PositionParser.MAX_TEXT)
            if (to.isEmpty() || title.isEmpty()) return@mapNotNull null
            TaskRequest(
                to = to,
                title = title,
                why = obj.optString("why").trim().take(MAX_TEXT),
                expect = obj.optString("expect").trim().take(MAX_TEXT),
            )
        }.take(MAX_TASKS)
    }

    /**
     * `evidence` may be a list of ids, a list of URLs, or (a common model habit)
     * one comma-joined string. All three are read; nothing is inferred.
     */
    private fun stringList(value: Any?): List<String> = when (value) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> (0 until value.length()).mapNotNull { i ->
            value.optString(i).trim().takeIf { it.isNotEmpty() }
        }
        is String -> value.split(',', ';', ' ')
            .map { it.trim().trim('"', '[', ']') }
            .filter { it.isNotEmpty() }
        else -> emptyList()
    }.distinct().take(8)

    // ── JSON recovery ──────────────────────────────────────────────────────

    /**
     * Lifts the first balanced JSON object out of the text.
     *
     * Balanced, not "first { to last }": a model that explains its answer after
     * the JSON puts braces in the explanation, and slicing to the last brace
     * produced unparseable text in the reference implementation for exactly that
     * reason. String literals are tracked so a `}` inside a quoted sentence does
     * not end the object.
     */
    fun extractJsonObject(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = stripFence(raw.trim())
        val start = text.indexOfFirst { it == '{' }
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = text.substring(start, i + 1)
                        return repair(candidate)
                    }
                }
            }
        }
        // Unbalanced: the model ran out of tokens. Try the remainder anyway —
        // a position missing its last brace still carries the claim.
        return repair(text.substring(start))
    }

    private fun stripFence(text: String): String {
        if (!text.startsWith("```")) return text
        val firstNewline = text.indexOf('\n')
        if (firstNewline < 0) return text
        val body = text.substring(firstNewline + 1)
        val end = body.lastIndexOf("```")
        return if (end >= 0) body.substring(0, end) else body
    }

    /**
     * The two repairs that are mechanical and safe: a trailing comma before a
     * closing bracket, and a missing trailing brace. Anything deeper (single
     * quotes, unescaped newlines inside strings) is left alone — those repairs
     * require guessing where a string ends, and guessing there can move text
     * from one field into another, which is the one thing a parser must not do.
     */
    private fun repair(json: String): String {
        var out = json.trim().replace(Regex(""",(\s*[}\]])"""), "$1")
        var depth = 0
        var inString = false
        var escaped = false
        for (c in out) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> depth--
            }
        }
        if (inString) out += "\""
        while (depth > 0) {
            out += "}"
            depth--
        }
        return out
    }
}
