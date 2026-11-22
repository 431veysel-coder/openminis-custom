package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.data.TranslationPacks
import com.openminis.app.data.TranslationPrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.StopReasons
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
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
 * and the caller shows "3/7" while it runs.
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
 *
 * ## [37099] Dört sessiz hata burada kapatıldı
 *
 * Bu sürümün tamamı, ölçülmüş dört davranış hatasının düzeltmesidir:
 *
 * 1. **`stopReason` yok sayılıyordu.** `maxTokens` sınırına takılan bir parça
 *    kesik metinle dönüyor, `trim()` edilip **tamamlanmış sayılıyor** ve
 *    önbelleğe tam çeviri olarak yazılıyordu. Kullanıcı cümlenin ortasında
 *    biten bir çeviri görüyor, tekrar bastığında önbellekten aynı kesik metin
 *    geliyordu — kalıcı, sessiz bozulma. Artık kesilme ölçülüyor, bir kez
 *    daha geniş bütçeyle yeniden denenıyor ve yine kesilirse **tam sayılmıyor**.
 * 2. **Kısmi kayıt "tam" gibi dönüyordu.** `readCache` tam yoksa kısmiyi
 *    döndürüyordu ve `translate` onu sonuç sayıp çıkıyordu: 7 parçanın 3'ü
 *    çevrilmişse kullanıcı sonsuza kadar yarım metin görüyor, yeniden basmak
 *    hiçbir şey yapmıyordu. Artık kısmi kayıt **kaldığı yerden devam ediyor**
 *    ve arayüz "kısmi" olduğunu söylüyor.
 * 3. **İptal, ödenmiş tokenları çöpe atıyordu.** Kısmi yazım yalnız döngü
 *    bittikten sonra yapılıyordu; kullanıcı sohbetten çıkınca (ya da blok
 *    kaydırılıp kompozisyondan düşünce) çevrilmiş parçalar kayboluyor ve
 *    sonraki deneme baştan ödüyordu. Artık **her parçadan sonra** yazılıyor.
 * 4. **Parçalar sırayla çevriliyordu.** Yedi parça = yedi gidiş-dönüş. Bu
 *    gecikmenin asıl sebebiydi (oracle: her parça 2-6 sn). Artık
 *    [CONCURRENCY] kadar paralel, sıra korunarak.
 */
internal object ThinkingTranslator {

    private const val TAG = "ThinkingXlate"

    /**
     * Characters per request. Comfortably below every current provider's
     * limits, and small enough that one failed chunk is a small loss.
     */
    private const val CHUNK_CHARS = 3000

    /**
     * Output budget floor per chunk.
     *
     * [37099] 2048'den 4096'ya çıkarıldı. Sebep ölçüm: CJK→Türkçe çeviride
     * çıktı **karakterce** değil **tokenca** büyüyor (tek Han karakteri ~1
     * token, karşılığı 1-3 token). 3000 karakterlik bir parça 2048 tokenla
     * kesiliyordu ve o kesik metin tam çeviri olarak önbelleğe giriyordu.
     */
    private const val BASE_OUTPUT_TOKENS = 4096

    /** Bir parça için üst sınır; kesilme görülürse bir kez buraya çıkılır. */
    private const val HARD_OUTPUT_TOKENS = 16_384

    /**
     * [37099] Aynı anda çevrilen parça sayısı.
     *
     * 1'den 2'ye çıkarmanın bedeli: ücretsiz kademelerde eşzamanlı istek
     * 429 üretebilir. Bu yüzden 2 — "hızlı" ile "kota yakan" arasındaki
     * en ucuz denge. Sıra korunuyor: sonuçlar dizinle birleştiriliyor.
     */
    private const val CONCURRENCY = 2

    private const val CACHE_DIR = "thinking-translations"

    /** Cache ceiling; oldest files are dropped past this. */
    private const val MAX_CACHE_FILES = 400

    /** Kesilmiş sayılan durma nedenleri — Gemini büyük harf gönderir. */
    private val TRUNCATED_REASONS = setOf("length", "max_tokens", "MAX_TOKENS", "max_output_tokens")

    /**
     * Çevirinin sonucu — **tamamlanmışlık bilgisi metnin parçası**.
     *
     * [37099] Önce `String?` dönüyordu ve "tamam" ile "3/7" ayırt edilemiyordu.
     * Arayüz tam olmayan bir çeviriyi tam gibi gösterdiği için kullanıcı
     * metnin neden ortada bittiğini anlamıyordu.
     */
    data class Out(
        val text: String,
        /** true: bütün parçalar çevrildi ve hiçbiri kesilmedi. */
        val complete: Boolean,
        val done: Int,
        val total: Int,
        /** true: en az bir parça `maxTokens` sınırına takıldı. */
        val truncated: Boolean = false,
        /** [37099] true: sonuç sözlük tabanlı, cümle çevirisi değil. */
        val degraded: Boolean = false,
    )

    private data class Cached(val out: Out)

    /**
     * [MINIS-OZEL] The prompt, plus an installed translation pack.
     *
     * The base rules are the same for every call; a pack appends terminology
     * and style so consecutive blocks agree with each other instead of each
     * request inventing its own translation for "context window". The pack is
     * resolved per language, so a Turkish pack can never shape a German
     * translation.
     */
    private fun systemPrompt(targetLanguage: String, pack: TranslationPacks.Pack? = null): String = buildString {
        append("You are a translation engine. Translate the user's text into $targetLanguage.\n")
        append("Rules:\n")
        append("- Output ONLY the translation. No preamble, no explanation, no quotes around it.\n")
        append("- Preserve the original structure: paragraph breaks, line breaks, lists, numbering.\n")
        append("- Do NOT translate code, identifiers, file paths, URLs, variable names, or ")
        append("mathematical notation; leave them exactly as they are.\n")
        append("- Preserve the author's tone and level of technical detail.\n")
        append("- If the text is already in $targetLanguage, return it unchanged.\n")
        // Appended after the rules on purpose: the rules are the contract, the
        // pack is the terminology, and a pack must not be able to weaken them.
        val fragment = TranslationPacks.promptFragment(pack)
        if (fragment.isNotBlank()) {
            append("\nTerminology pack (follow it strictly):\n")
            append(fragment)
        }
    }

    /**
     * Translate [text] into [targetLanguage].
     *
     * Returns null only when there is nothing usable at all (no model, no
     * provider, every chunk failed). A partial result comes back as
     * [Out.complete] = false with the chunks that did arrive, so the caller can
     * show it and offer to continue.
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
    ): Out? {
        if (text.isBlank()) return Out("", complete = true, done = 0, total = 0)

        val entry = resolveEntry(repo, modelEntryId)
        if (entry == null) {
            AppLogger.info(TAG, "[xlate] no model entry available — nothing to do")
            return null
        }

        // [MINIS-OZEL 11.1] The engine choice decides whether the pack takes
        // part at all. MODEL_ONLY is a real option, not a hidden one: someone
        // translating a message in a hurry wants the tokens spent on the text,
        // not on 120 glossary entries that will not come up.
        val pack = if (TranslationPrefs.engine(context) == TranslationPrefs.Engine.MODEL_WITH_PACK) {
            TranslationPacks.forLanguage(context, targetLanguage)
        } else {
            null
        }
        // The pack is part of the cache key: installing one must not return
        // translations produced before it existed, or the feature looks broken
        // until every cached block is translated again by hand.
        val cacheKey = cacheKey(targetLanguage, entry.id, text, pack?.id ?: "-")

        // [37099] Zincirin ilk halkası: çevrimdışı sözlük.
        //
        // Bedava ve anında; ama cümle çevirmiyor. Zincirde model varsa ve
        // sözlük sonucu "tam" değilse modele düşülür (aşağıda). Model hiç
        // yoksa sözlük sonucu yine döner — "hiç yok" her zaman "kısmi var"dan
        // kötüdür — ama `complete = false` ve `degraded` başlığıyla, tam
        // çeviri gibi gösterilmez.
        val plan = TranslationPrefs.chain(context)
        if (plan.firstOrNull() == TranslationChain.Engine.OFFLINE_PACK && pack != null) {
            val offline = OfflineGlossary.apply(text, pack)
            if (offline.usable && offline.complete) {
                onProgress(chunks.size, chunks.size)
                return Out(offline.text, complete = true, done = chunks.size, total = chunks.size)
            }
            val modelsLeft = plan.any {
                it == TranslationChain.Engine.MODEL_PACK || it == TranslationChain.Engine.MODEL_ONLY
            }
            if (offline.usable && !modelsLeft) {
                AppLogger.info(TAG, "[xlate] offline glossary only: ${offline.replaced} terim")
                return Out(offline.text, complete = false, done = 0, total = 1, degraded = true)
            }
        }

        val chunks = chunk(text)

        // [37099] Kısmi kayıt artık "tam" gibi dönmüyor: kaldığı yerden devam.
        val cached = readCache(context, cacheKey)
        if (cached != null) {
            val c = cached.out
            if (c.complete || (!c.complete && c.done >= chunks.size)) {
                AppLogger.info(TAG, "[xlate] cache hit chars=${text.length} lang=$targetLanguage complete=${c.complete}")
                return c
            }
        }
        var done = cached?.out?.done?.coerceIn(0, chunks.size) ?: 0
        val out = StringBuilder(cached?.out?.text?.takeIf { done > 0 }.orEmpty())
        var truncated = cached?.out?.truncated ?: false
        onProgress(done, chunks.size)

        val provider = TitleCandidates.providerFor(
            repo, context, entry,
            // A translation belongs to no conversation of its own, and an
            // OpenCode Go entry answers 400 without an id.
            sessionId = sessionId ?: ProviderFactory.oneOffSessionId(),
        ) ?: run {
            AppLogger.info(TAG, "[xlate] provider creation failed entry=${entry.model.id}")
            return cached?.out
        }

        val prompts = systemPrompt(targetLanguage, pack)
        val semaphore = Semaphore(CONCURRENCY)

        /**
         * Tek parçayı çevirir. `null` = bu parça alınamadı, orada dur.
         *
         * Kesilme iki kademeli: bir kez daha geniş bütçeyle denenir; yine
         * kesilirse metin döner ama [Out.complete] asla true olmaz ve bu parça
         * `done` sayılmaz — böylece bir sonraki deneme **aynı parçayı** yeniden
         * dener, kullanıcı da yarım cümleyi tam sanmaz.
         */
        suspend fun translateChunk(index: Int): String? = semaphore.withPermit {
            coroutineContext.ensureActive()
            var budget = maxOf(BASE_OUTPUT_TOKENS, chunkBudget(chunks[index]))
            repeat(2) { attempt ->
                val response = try {
                    provider.sendMessage(
                        messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = chunks[index])),
                        systemPrompt = prompts,
                        maxTokens = budget,
                        // null omits the field entirely — several models 400 on
                        // anything but their fixed temperature.
                        temperature = null,
                        thinkingLevel = ThinkingLevel.OFF,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    AppLogger.info(
                        TAG,
                        "[xlate] chunk ${index + 1}/${chunks.size} failed: ${t.javaClass.simpleName}: ${t.message}",
                    )
                    return@withPermit null
                }
                val piece = response.text.trim()
                if (piece.isEmpty()) {
                    AppLogger.info(TAG, "[xlate] chunk ${index + 1}/${chunks.size} came back empty")
                    return@withPermit null
                }
                val cut = isTruncated(response.stopReason)
                if (!cut) return@withPermit piece
                truncated = true
                AppLogger.info(
                    TAG,
                    "[xlate] chunk ${index + 1}/${chunks.size} truncated stop=${response.stopReason} " +
                        "attempt=${attempt + 1} tokens=$budget",
                )
                if (attempt == 1) return@withPermit null
                budget = minOf(HARD_OUTPUT_TOKENS, budget * 2)
            }
            null
        }

        while (done < chunks.size) {
            // Bir pencere kadar paralel çevir; sıra korunur, ilk boşlukta durulur.
            val window = (done until minOf(chunks.size, done + CONCURRENCY * 2)).toList()
            val pieces = coroutineScope {
                window.map { idx -> async { idx to translateChunk(idx) } }.awaitAll()
            }.sortedBy { it.first }

            var advanced = false
            for ((idx, piece) in pieces) {
                if (idx != done) break          // sıra bozuldu: boşluğu atlama
                if (piece == null) break        // bu parça alınamadı: burada dur
                if (out.isNotEmpty()) out.append("\n\n")
                out.append(piece)
                done = idx + 1
                advanced = true
                // [37099] Her parçadan SONRA yaz: iptal edilen bir çevirinin
                // ödenmiş parçaları kaybolmasın.
                writeCache(context, cacheKey, Out(out.toString(), complete = false, done = done, total = chunks.size, truncated = truncated))
                onProgress(done, chunks.size)
            }
            if (!advanced) break
        }

        if (out.isEmpty()) return null
        val result = Out(
            text = out.toString(),
            complete = done == chunks.size && !truncated,
            done = done,
            total = chunks.size,
            truncated = truncated,
        )
        // [37096] Tam çeviri tam anahtara yazılır; [37099] kesik/kısmi sonuç
        // `complete = false` ile yazılır ki bir sonraki açılışta **devam**
        // edilebilsin, "bitmiş" sayılmasın.
        writeCache(context, cacheKey, result)
        AppLogger.info(
            TAG,
            "[xlate] done chars=${text.length}->${result.text.length} chunks=$done/${chunks.size} " +
                "complete=${result.complete} truncated=$truncated model=${entry.model.id} lang=$targetLanguage",
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

    /** [37099] Kesilme ölçümü — tek yerde, iki yerde tekrarlanmasın. */
    private fun isTruncated(stopReason: String?): Boolean {
        val r = stopReason?.trim().orEmpty()
        if (r.isEmpty()) return false
        if (r in TRUNCATED_REASONS) return true
        return StopReasons.isMaxTokens(r)
    }

    // ── chunking ───────────────────────────────────────────────────────────

    /**
     * [37099] Parça başına çıktı bütçesi.
     *
     * Neden `length/2` değil: o hesap "çıktı tokenı ≈ karakter/2" varsayıyor,
     * yani Latin alfabesi için doğru, CJK için **yarısı**. Parçanın kendi
     * uzunluğunu üst sınır kabul etmek, hangi yazı sisteminde olursa olsun
     * kesilmemeyi garanti eder (token sayısı karakter sayısını aşamaz).
     */
    private fun chunkBudget(chunk: String): Int = minOf(HARD_OUTPUT_TOKENS, chunk.length / 2 + chunk.length)

    /**
     * Split on paragraph boundaries, accumulating up to [CHUNK_CHARS]. A single
     * paragraph longer than that is hard-split, so no chunk can exceed the
     * budget no matter how the source is formatted.
     *
     * [37099] `internal` (özel değil): parçalama saf bir fonksiyon ve cihazsız
     * test edilebilir olması gerek; bu cihazda JVM koşmadığı için tek şansımız
     * saf fonksiyonu test etmek.
     */
    internal fun chunk(text: String): List<String> {
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
    internal fun sentenceBoundary(s: String, limit: Int): Int {
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

    private fun cacheKey(lang: String, modelId: String, text: String, packId: String = "-"): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(lang.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(modelId.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(packId.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(text.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * [37099] Önbellek: tek dosya, tamamlanmışlık **içerikte**.
     *
     * Önceki sürüm iki ayrı dosya tutuyordu (`{key}.txt` ve `{key}:partial.txt`)
     * ve okuyan taraf hangisinin tam olduğunu bilemiyordu; pratikte kısmi
     * kayıt tam sanılıp dönüyordu. Şimdi tek JSON kaydı `done/total/text`
     * taşıyor, böylece okuma "nerede kaldım" sorusunu cevaplayabiliyor.
     */
    private fun readCache(ctx: Context, key: String): Cached? = runCatching {
        val f = File(cacheDir(ctx), "$key.json")
        if (!f.isFile) return@runCatching null
        val obj = JSONObject(f.readText())
        val text = obj.optString("text", "")
        if (text.isBlank()) return@runCatching null
        // Touch so the prune keeps recently-read entries.
        f.setLastModified(System.currentTimeMillis())
        Cached(
            Out(
                text = text,
                complete = obj.optBoolean("complete", false),
                done = obj.optInt("done", 0),
                total = obj.optInt("total", 0),
                truncated = obj.optBoolean("truncated", false),
            ),
        )
    }.getOrNull()

    private fun writeCache(ctx: Context, key: String, value: Out) {
        runCatching {
            val obj = JSONObject()
                .put("text", value.text)
                .put("complete", value.complete)
                .put("done", value.done)
                .put("total", value.total)
                .put("truncated", value.truncated)
            File(cacheDir(ctx), "$key.json").writeText(obj.toString())
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
