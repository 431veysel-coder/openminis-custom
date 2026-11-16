package com.openminis.app.data

import android.content.Context
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * [MINIS-OZEL] Translation packs — downloadable translation knowledge.
 *
 * ## The problem
 *
 * Thinking-block translation sends the text to a model with a six-line system
 * prompt and no memory. Three consequences, all of them visible in real use:
 *
 *  - **Terminology drifts.** "context window" becomes "bağlam penceresi" in one
 *    block and "içerik penceresi" in the next, because each request is
 *    independent.
 *  - **The same block is paid for twice** — once per device. The cache is
 *    per-install and keyed by model, so switching models throws it away.
 *  - **Nothing can be corrected.** When a translation is wrong in a specific
 *    way (a product name translated, a code identifier mangled), there is no
 *    place to say so.
 *
 * A pack answers all three: it is a small JSON file (a few KB) holding a
 * glossary, style rules, identifiers that must stay untouched, and optional
 * few-shot examples. It is downloaded once, stored in the app's files
 * directory, and injected into the prompt of every translation of that
 * language.
 *
 * ## Why JSON and not an on-device model
 *
 * An offline NMT model would remove the cost entirely, but it needs a real
 * model (Marian/NLLB ONNX, 30-100 MB quantised) plus a SentencePiece
 * tokenizer — a native dependency this app does not carry (the ONNX runtime
 * that ships here belongs to the Silero VAD library and has neither). That is
 * a different project with a different risk profile. A pack costs one
 * download of a few kilobytes and improves a translation that already works.
 *
 * ## Where packs come from
 *
 * The repo publishes an index at `packs/index.json`. A pack may also be
 * installed from any URL, or from a JSON file the user picks — the format is
 * open and documented in [Pack].
 */
object TranslationPacks {

    private const val TAG = "TranslationPacks"

    /** Directory under filesDir; survives app updates, dies on uninstall. */
    private const val DIR = "translation-packs"

    /** The published catalogue. Failure is not an error: built-ins still work. */
    const val INDEX_URL = "https://raw.githubusercontent.com/431veysel-coder/openminis-custom/main/packs/index.json"

    private const val PREFS = "thinking_translation_prefs"
    private const val KEY_ACTIVE = "pack_id"

    /**
     * One pack. Everything is optional except [id] and [name]; a pack that only
     * carries a glossary is still useful, and a pack with nothing in it is
     * rejected rather than installed (it would only add prompt tokens).
     */
    data class Pack(
        val id: String,
        val name: String,
        /** e.g. "Turkish". Matched case-insensitively against the target language. */
        val targetLanguage: String,
        val version: Int = 1,
        val description: String = "",
        /** Free-form instruction appended to the system prompt. */
        val style: String = "",
        /** source term -> preferred translation. */
        val glossary: Map<String, String> = emptyMap(),
        /** Identifiers that must never be translated (product names, APIs). */
        val keepAsIs: List<String> = emptyList(),
        /** Optional few-shot pairs, "source" -> "target". */
        val examples: List<Pair<String, String>> = emptyList(),
        /** Where it came from, for the settings row. */
        val sourceUrl: String? = null,
        val builtIn: Boolean = false,
    ) {
        val isEmpty: Boolean
            get() = style.isBlank() && glossary.isEmpty() && keepAsIs.isEmpty() && examples.isEmpty()

        /** Rough prompt cost, shown before the user installs it. */
        val promptChars: Int
            get() = buildString {
                append(style)
                glossary.forEach { (k, v) -> append(k).append(v) }
                keepAsIs.forEach { append(it) }
                examples.forEach { (s, t) -> append(s).append(t) }
            }.length

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("targetLanguage", targetLanguage)
            put("version", version)
            put("description", description)
            put("style", style)
            put("glossary", JSONObject().apply {
                glossary.forEach { (k, v) -> put(k, v) }
            })
            put("keepAsIs", JSONArray(keepAsIs))
            put("examples", JSONArray().apply {
                examples.forEach { (s, t) ->
                    put(JSONObject().put("source", s).put("target", t))
                }
            })
        }
    }

    /** A pack in the shipped catalogue, before install. */
    data class CatalogEntry(val pack: Pack, val url: String)

    // ── storage ─────────────────────────────────────────────────────────────

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { mkdirs() }

    private fun fileFor(context: Context, id: String): File =
        File(dir(context), "${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    fun installed(context: Context): List<Pack> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { parse(f.readText(), sourceUrl = null) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun installedSizeBytes(context: Context): Long =
        dir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun remove(context: Context, id: String): Boolean {
        val ok = fileFor(context, id).delete()
        if (activeId(context) == id) setActive(context, null)
        AppLogger.info(TAG, "[remove] $id -> $ok")
        return ok
    }

    fun setActive(context: Context, id: String?) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (id.isNullOrBlank()) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id)
            }
            .apply()
    }

    fun activeId(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, null)

    /**
     * The pack to apply for [targetLanguage]: the active one when it matches,
     * else the first installed pack for that language. Null when nothing
     * applies — a pack for German must not shape a translation into Turkish.
     */
    fun forLanguage(context: Context, targetLanguage: String): Pack? {
        val lang = targetLanguage.trim()
        if (lang.isEmpty()) return null
        val all = installed(context).filter { it.targetLanguage.equals(lang, ignoreCase = true) }
        if (all.isEmpty()) return null
        val active = activeId(context)
        return all.firstOrNull { it.id == active } ?: all.first()
    }

    // ── install ─────────────────────────────────────────────────────────────

    /** Validates and stores [json]. Returns the pack, or a message on failure. */
    fun installJson(context: Context, json: String, sourceUrl: String? = null): Result<Pack> =
        runCatching {
            val pack = parse(json, sourceUrl)
            require(!pack.isEmpty) { "pack '${pack.id}' carries no rules" }
            fileFor(context, pack.id).writeText(pack.toJson().toString(2))
            AppLogger.info(
                TAG,
                "[install] ${pack.id} v${pack.version} lang=${pack.targetLanguage} " +
                    "glossary=${pack.glossary.size} keep=${pack.keepAsIs.size} " +
                    "examples=${pack.examples.size} chars=${pack.promptChars}",
            )
            pack
        }

    /**
     * Downloads and installs a pack. 15 s connect / 20 s read: a pack is a few
     * kilobytes, so a slow response means the URL is wrong, not that the
     * network is slow.
     */
    suspend fun installFromUrl(context: Context, url: String): Result<Pack> = runCatching {
        val body = httpGet(url)
        installJson(context, body, sourceUrl = url).getOrThrow()
    }

    /** The shipped catalogue. Never throws: a dead network is not an app error. */
    suspend fun catalog(): Result<List<CatalogEntry>> = runCatching {
        val body = httpGet(INDEX_URL)
        val root = JSONObject(body)
        val arr = root.optJSONArray("packs") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val packObj = o.optJSONObject("pack")
            val url = o.optString("url", "")
            if (packObj == null || url.isBlank()) return@mapNotNull null
            runCatching { parse(packObj.toString(), sourceUrl = url) to url }
                .getOrNull()?.let { (p, u) -> CatalogEntry(p, u) }
        }
    }

    // ── parsing ─────────────────────────────────────────────────────────────

    internal fun parse(raw: String, sourceUrl: String?): Pack {
        val o = JSONObject(raw)
        val id = o.optString("id").trim()
        val name = o.optString("name").trim()
        require(id.isNotEmpty()) { "pack has no id" }
        require(name.isNotEmpty()) { "pack has no name" }

        val glossary = LinkedHashMap<String, String>()
        o.optJSONObject("glossary")?.let { g ->
            g.keys().forEach { k -> glossary[k] = g.optString(k) }
        }
        val keep = buildList {
            o.optJSONArray("keepAsIs")?.let { a ->
                for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        val examples = buildList {
            o.optJSONArray("examples")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i) ?: continue
                    val s = e.optString("source")
                    val t = e.optString("target")
                    if (s.isNotBlank() && t.isNotBlank()) add(s to t)
                }
            }
        }
        return Pack(
            id = id,
            name = name,
            targetLanguage = o.optString("targetLanguage", "").trim(),
            version = o.optInt("version", 1),
            description = o.optString("description", ""),
            style = o.optString("style", ""),
            glossary = glossary,
            keepAsIs = keep,
            examples = examples,
            sourceUrl = sourceUrl,
            builtIn = false,
        )
    }

    // ── prompt contribution ─────────────────────────────────────────────────

    /**
     * The pack as prompt lines. Empty for a null pack, so callers do not branch.
     *
     * Glossary entries are capped at [MAX_GLOSSARY] because a pack with 5000
     * terms would cost more than the text being translated and the model would
     * start ignoring the instruction that says "output only the translation".
     */
    fun promptFragment(pack: Pack?): String {
        if (pack == null) return ""
        return buildString {
            if (pack.style.isNotBlank()) {
                append("Style for this language:\n").append(pack.style.trim()).append('\n')
            }
            if (pack.glossary.isNotEmpty()) {
                append("Use these translations exactly:\n")
                pack.glossary.entries.take(MAX_GLOSSARY).forEach { (k, v) ->
                    append("- ").append(k).append(" -> ").append(v).append('\n')
                }
            }
            if (pack.keepAsIs.isNotEmpty()) {
                append("Never translate these; copy them verbatim:\n")
                append(pack.keepAsIs.take(MAX_GLOSSARY).joinToString(", ")).append('\n')
            }
            if (pack.examples.isNotEmpty()) {
                append("Examples of the expected output:\n")
                pack.examples.take(MAX_EXAMPLES).forEach { (s, t) ->
                    append("SOURCE: ").append(s).append('\n')
                    append("TARGET: ").append(t).append('\n')
                }
            }
        }
    }

    private const val MAX_GLOSSARY = 120
    private const val MAX_EXAMPLES = 3

    // ── http ────────────────────────────────────────────────────────────────

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "minis-ozel")
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from $url")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            // A pack is small by construction; anything larger is not a pack.
            require(bytes.size <= 512 * 1024) { "response is ${bytes.size} bytes — too large for a pack" }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }
}
