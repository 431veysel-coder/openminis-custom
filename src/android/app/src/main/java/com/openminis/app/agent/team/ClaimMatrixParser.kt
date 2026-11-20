package com.openminis.app.agent.team

import org.json.JSONObject

/**
 * [MINIS-OZEL 11.5 · 37037] Reads the chair's canonical claim set.
 *
 * The chair is the one model in the council with real power: it decides that
 * two members' arguments are "the same claim". That power is why this parser is
 * stricter than [PositionParser] — three specific refusals:
 *
 *  1. **An unknown member name is dropped, never mapped to someone real.** The
 *     failure it prevents: a chair inventing "Member 3" and the parser
 *     reassigning it to whoever is first, so a support vote appears out of
 *     nowhere. A missing stance understates agreement, which the report can
 *     show; an invented one cannot be seen at all.
 *  2. **A claim with no stances is kept but marked ORPHAN** by [StanceMath], not
 *     silently discarded — the chair proposing a claim nobody argued about is
 *     information about the chair.
 *  3. **No id is invented from a number that could collide.** Ids are taken from
 *     the model when present and made unique; otherwise `C<n>` by position.
 */
object ClaimMatrixParser {

    const val MAX_CLAIMS = 20

    fun parse(raw: String?, members: List<String>): ClaimMatrix? {
        val json = PositionParser.extractJsonObject(raw) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val arr = obj.optJSONArray("claims") ?: return null
        val canonicalNames = members.associateBy { it.lowercase() }

        val used = mutableSetOf<String>()
        val claims = mutableListOf<Claim>()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val text = item.optString("text").trim().take(PositionParser.MAX_TEXT)
            if (text.isEmpty()) continue

            val stances = mutableMapOf<String, Stance>()
            val rawStances = item.optJSONObject("stances")
            if (rawStances != null) {
                // Walk the chair's OWN keys and map each back to a real member by
                // exact or case-insensitive name. Iterating our roster instead
                // would silently lose every stance the chair spelled differently
                // ("ada" vs "Ada"), which understates agreement in a way the
                // report cannot show.
                val keys = rawStances.keys()
                while (keys.hasNext()) {
                    val rawName = keys.next()
                    val canonical = canonicalNames[rawName.trim().lowercase()] ?: continue
                    stances[canonical] = Stance.parse(rawStances.optString(rawName))
                }
            }

            val id = uniqueId(item.optString("id"), i + 1, used)
            claims += Claim(
                id = id,
                text = text,
                stances = stances,
                evidence = evidenceIds(item.opt("evidence")),
                falsifiable = item.optBoolean("falsifiable", true),
                // [37052] The chair's "this needs a measurement" signal. Read as
                // false when absent: an unmarked claim is the normal case, and a
                // missing field must not open a task on every claim ever parsed.
                needsTask = item.optBoolean("needs_task", false),
            )
            if (claims.size >= MAX_CLAIMS) break
        }

        val droppedArr = obj.optJSONArray("dropped")
        val dropped = if (droppedArr == null) {
            emptyList()
        } else {
            (0 until droppedArr.length()).mapNotNull { i ->
                droppedArr.optString(i).trim().takeIf { it.isNotEmpty() }
            }
        }

        if (claims.isEmpty()) return null
        return ClaimMatrix(claims = claims, dropped = dropped.take(MAX_CLAIMS))
    }

    private fun uniqueId(raw: String, ordinal: Int, used: MutableSet<String>): String {
        var candidate = raw.trim().take(12)
        if (candidate.isEmpty() || !candidate.any { it.isLetterOrDigit() }) candidate = "C$ordinal"
        var n = 2
        val base = candidate
        while (!used.add(candidate)) {
            candidate = "$base-$n"
            n++
        }
        return candidate
    }

    private fun evidenceIds(value: Any?): List<String> {
        val items: List<String> = when (value) {
            null, JSONObject.NULL -> emptyList()
            is String -> value.split(',', ';').map { it.trim() }
            is org.json.JSONArray -> (0 until value.length()).map { i -> value.optString(i).trim() }
            else -> emptyList()
        }
        return items.filter { it.isNotEmpty() }.distinct().take(12)
    }
}
