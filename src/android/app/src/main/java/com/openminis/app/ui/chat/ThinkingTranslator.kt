package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * [MINIS-OZEL] Translates a reasoning ("thinking") block into the user's own
 * language.
 *
 * Reasoning models frequently think in English or Chinese regardless of the
 * language they answer in, which leaves a non-multilingual reader with an
 * unreadable block. This turns that block into their language on demand.
 *
 * ## Why it is chunked
 *
 * A thinking block can be 200k characters. One request carrying all of it
 * would blow both the provider's request size limit and the model's output
 * budget, and would fail as a whole instead of partially. Chunks of
 * [CHUNK_CHARS] are translated in order, so a long block still makes progress
 * and the caller can show "3/7" while it runs.
 *
 * ## Why it is cached
 *
 * Translation costs real tokens. Re-opening a block, or collapsing and
 * expanding it again, must not re-bill the user. The cache key includes the
 * language, the model and a hash of the source text, so editing the source (a
 * still-streaming block) misses the cache while re-reading hits it.
 *
 * ## Why thinking is OFF
 *
 * This is a mechanical transform. A reasoning model left on would spend its
 * budget re-deriving what it is being asked to translate, and on some models
 * burn the whole allowance before emitting any content.
 */
internal object ThinkingTranslator {

    private const val TAG = "ThinkingXlate"

    /**
     * Characters per request. Comfortably below every current provider's
     * limits, and small enough that one failed chunk is a small loss.
     */
    private const val CHUNK_CHARS = 3000

    /** Output budget per chunk; a translation cannot be much longer than its source. */
    private const val BASE_OUTPUT_TOKENS = 2048

    private const val CACHE_DIR = "thinking-translations"

    /** Cache ceiling; oldest files are dropped past this. */
    private const val MAX_CACHE_FILES = 400

    private fun systemPrompt(targetLanguage: String): String = buildString {
        append("You are a translation engine. Translate the user's text into $targetLanguage.\n")
        append("Rules:\n")
        append("- Output ONLY the translation. No preamble, no explanation, no quotes around it.\n")
        append("- Preserve the original structure: paragraph breaks, line breaks, lists, numbering.\n")
        append("- Do NOT translate code, identifiers, file paths, URLs, variable names, or ")
        append("mathematical notation; leave them exactly as they are.\n")
        append("- Preserve the author's tone and level of technical detail.\n")
        append("- If the text is already in $targetLanguage, return it unchanged.\n")
    }

    /**
     * Translate [text] into [targetLanguage], returning the translation, or
     * null when no suitable model is reachable.
     *
     * Never throws for an ordinary failure — a translation button must not be
     * able to take the chat screen down.
     */
    suspend fun translate(
        repo: ProviderRepository,
        context: Context,
        text: String,
        targetLanguage: String,
        modelEntryId: String?,
        sessionId: String?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String? {
        if (text.isBlank()) return ""

        val entry = resolveEntry(repo, modelEntryId)
        if (entry == null) {
            AppLogger.info(TAG, "[xlate] no model entry available — nothing to do")
            return null
        }

        val cacheKey = cacheKey(targetLanguage, entry.id, text)
        readCache(context, cacheKey)?.let {
            AppLogger.info(TAG, "[xlate] cache hit chars=${text.length} lang=$targetLanguage")
            return it
        }

        val provider = TitleCandidates.providerFor(
            repo, context, entry,
            // A translation belongs to no conversation of its own, and an
            // OpenCode Go entry answers 400 without an id.
            sessionId = sessionId ?: ProviderFactory.oneOffSessionId(),
        ) ?: run {
            AppLogger.info(TAG, "[xlate] provider creation failed entry=${entry.model.id}")
            return null
        }

        val chunks = chunk(text)
        val out = StringBuilder()
        var done = 0
        for ((index, chunk) in chunks.withIndex()) {
            coroutineContext.ensureActive()
            val piece = try {
                provider.sendMessage(
                    messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = chunk)),
                    systemPrompt = systemPrompt(targetLanguage),
                    maxTokens = maxOf(BASE_OUTPUT_TOKENS, chunk.length / 2),
                    // null omits the field entirely — several models 400 on
                    // anything but their fixed temperature.
                    temperature = null,
                    thinkingLevel = ThinkingLevel.OFF,
                ).text.trim()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLogger.info(
                    TAG,
                    "[xlate] chunk ${index + 1}/${chunks.size} failed: ${t.javaClass.simpleName}: ${t.message}",
                )
                // Keep what earlier chunks produced: a partial translation is
                // still more useful than nothing.
                break
            }
            if (piece.isEmpty()) {
                AppLogger.info(TAG, "[xlate] chunk ${index + 1}/${chunks.size} came back empty")
                break
            }
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(piece)
            done = index + 1
            onProgress(done, chunks.size)
        }

        val result = out.toString()
        if (result.isBlank()) return null
        if (done == chunks.size) writeCache(context, cacheKey, result)
        AppLogger.info(
            TAG,
            "[xlate] done chars=${text.length}->${result.length} chunks=$done/${chunks.size} " +
                "model=${entry.model.id} lang=$targetLanguage",
        )
        return result
    }

    // ── model selection ────────────────────────────────────────────────────

    private fun resolveEntry(repo: ProviderRepository, modelEntryId: String?): ModelEntry? {
        val entries = repo.config.value.modelEntries.filter { !it.isHidden }
        if (entries.isEmpty()) return null
        if (!modelEntryId.isNullOrBlank()) {
            entries.firstOrNull { it.id == modelEntryId }?.let { return it }
        }
        // Fallback: a model the user already nominated for the agent loop.
        // Never an arbitrary entry — translating with a model the user did not
        // choose would spend quota they never agreed to.
        val loopIds = repo.config.value.agentLoopModelEntryIds
        return loopIds.firstNotNullOfOrNull { id -> entries.firstOrNull { it.id == id } }
    }

    // ── chunking ───────────────────────────────────────────────────────────

    /**
     * Split on paragraph boundaries, accumulating up to [CHUNK_CHARS]. A single
     * paragraph longer than that is hard-split, so no chunk can exceed the
     * budget no matter how the source is formatted.
     */
    private fun chunk(text: String): List<String> {
        if (text.length <= CHUNK_CHARS) return listOf(text)
        val out = ArrayList<String>(text.length / CHUNK_CHARS + 1)
        val current = StringBuilder()
        for (para in text.split("\n\n")) {
            var piece = para
            while (piece.isNotEmpty()) {
                val room = CHUNK_CHARS - current.length - if (current.isEmpty()) 0 else 2
                if (piece.length <= room) {
                    if (current.isNotEmpty()) current.append("\n\n")
                    current.append(piece)
                    piece = ""
                } else if (room <= 0) {
                    out.add(current.toString())
                    current.setLength(0)
                } else {
                    // Prefer a sentence boundary near the cut.
                    val cut = sentenceBoundary(piece, room)
                    if (current.isNotEmpty()) current.append("\n\n")
                    current.append(piece.substring(0, cut))
                    piece = piece.substring(cut).trimStart()
                    out.add(current.toString())
                    current.setLength(0)
                }
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    /** Index at or before [limit] where a sentence most plausibly ends. */
    private fun sentenceBoundary(s: String, limit: Int): Int {
        val floor = (limit * 7) / 10
        for (i in minOf(limit, s.length - 1) downTo floor) {
            val c = s[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '。' || c == '！' || c == '？') {
                return i + 1
            }
        }
        return minOf(limit, s.length)
    }

    // ── cache ──────────────────────────────────────────────────────────────

    private fun cacheDir(ctx: Context): File =
        File(ctx.applicationContext.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }

    private fun cacheKey(lang: String, modelId: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(lang.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(modelId.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(text.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readCache(ctx: Context, key: String): String? = runCatching {
        val f = File(cacheDir(ctx), "$key.txt")
        if (!f.isFile) return null
        // Touch so the prune below keeps recently-read entries.
        f.setLastModified(System.currentTimeMillis())
        f.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun writeCache(ctx: Context, key: String, value: String) {
        runCatching {
            File(cacheDir(ctx), "$key.txt").writeText(value)
            pruneCache(ctx)
        }
    }

    private fun pruneCache(ctx: Context) {
        val files = cacheDir(ctx).listFiles() ?: return
        if (files.size <= MAX_CACHE_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHE_FILES)
            .forEach { it.delete() }
    }

    /** Size of the translation cache, for the settings screen. */
    fun cacheSizeBytes(ctx: Context): Long =
        cacheDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    /** Drops every cached translation. */
    fun clearCache(ctx: Context) {
        cacheDir(ctx).listFiles()?.forEach { it.delete() }
    }
}
