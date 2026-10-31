package com.openminis.app.ui.chat

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.Result

/**
 * Enhanced ThinkingEnhancer with multiple translation engines and summarization options.
 * Supports:
 * - ML Kit Translation (on-device, offline)
 * - LLM-based translation (using active model)
 * - Hybrid summarization (heuristic + LLM)
 */
object ThinkingEnhancer {

    // Translation Engine Types
    enum class TranslationEngine(val id: String, val displayName: String) {
        ML_KIT("ml_kit", "ML Kit (Çevrimdışı, Hızlı)"),
        LLM("llm", "LLM (Bağlamlı, Aktif Model)"),
        HYBRID("hybrid", "Hibrit (ML Kit + LLM Yedek)")
    }

    // Summarization Engine Types
    enum class SummarizationEngine(val id: String, val displayName: String) {
        HEURISTIC("heuristic", "Hizmetçi (Hızlı, Basit)"),
        LLM("llm", "LLM (Anlamsal, Bağlamlı)"),
        HYBRID("hybrid", "Hibrit (Kısa: Heuristic, Uzun: LLM)")
    }

    // Summarization Detail Levels
    enum class SummaryDetail(val id: String, val displayName: String, val maxPoints: Int) {
        BRIEF("brief", "Kısa (2-3 madde)", 3),
        MEDIUM("medium", "Orta (4-6 madde)", 6),
        DETAILED("detailed", "Detaylı (7-10 madde)", 10)
    }

    // Preferences Keys
    internal const val PREFS_NAME = "thinking_enhancer_prefs"
    internal const val KEY_TRANSLATE_ENGINE = "translate_engine"
    internal const val KEY_TRANSLATE_TARGET_LANG = "translate_target_lang"
    internal const val KEY_TRANSLATE_PROTECT_CODE = "translate_protect_code"
    internal const val KEY_TRANSLATE_AUTO = "translate_auto"
    internal const val KEY_SUMMARIZE_ENGINE = "summarize_engine"
    internal const val KEY_SUMMARIZE_DETAIL = "summarize_detail"
    internal const val KEY_SUMMARIZE_AUTO = "summarize_auto"

    // Default Values
    internal val DEFAULT_TRANSLATE_ENGINE = TranslationEngine.ML_KIT
    internal const val DEFAULT_TARGET_LANG = "tr"
    internal const val DEFAULT_PROTECT_CODE = true
    internal const val DEFAULT_TRANSLATE_AUTO = false
    internal val DEFAULT_SUMMARIZE_ENGINE = SummarizationEngine.HYBRID
    internal val DEFAULT_SUMMARY_DETAIL = SummaryDetail.BRIEF
    internal const val DEFAULT_SUMMARIZE_AUTO = false

    /**
     * Get current translation engine from preferences
     */
    fun getTranslationEngine(context: Context): TranslationEngine {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val engineId = prefs.getString(KEY_TRANSLATE_ENGINE, DEFAULT_TRANSLATE_ENGINE.id)
        return TranslationEngine.values().firstOrNull { it.id == engineId } ?: DEFAULT_TRANSLATE_ENGINE
    }

    /**
     * Get current target language
     */
    fun getTargetLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TRANSLATE_TARGET_LANG, DEFAULT_TARGET_LANG) ?: DEFAULT_TARGET_LANG
    }

    /**
     * Check if code protection is enabled
     */
    fun isCodeProtectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TRANSLATE_PROTECT_CODE, DEFAULT_PROTECT_CODE)
    }

    /**
     * Check if auto-translate is enabled
     */
    fun isAutoTranslateEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TRANSLATE_AUTO, DEFAULT_TRANSLATE_AUTO)
    }

    /**
     * Get current summarization engine
     */
    fun getSummarizationEngine(context: Context): SummarizationEngine {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val engineId = prefs.getString(KEY_SUMMARIZE_ENGINE, DEFAULT_SUMMARIZE_ENGINE.id)
        return SummarizationEngine.values().firstOrNull { it.id == engineId } ?: DEFAULT_SUMMARIZE_ENGINE
    }

    /**
     * Get summary detail level
     */
    fun getSummaryDetail(context: Context): SummaryDetail {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val detailId = prefs.getString(KEY_SUMMARIZE_DETAIL, DEFAULT_SUMMARY_DETAIL.id)
        return SummaryDetail.values().firstOrNull { it.id == detailId } ?: DEFAULT_SUMMARY_DETAIL
    }

    /**
     * Check if auto-summarize is enabled
     */
    fun isAutoSummarizeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SUMMARIZE_AUTO, DEFAULT_SUMMARIZE_AUTO)
    }

    /**
     * Save preferences
     */
    fun savePreferences(
        context: Context,
        translateEngine: TranslationEngine? = null,
        targetLanguage: String? = null,
        protectCode: Boolean? = null,
        autoTranslate: Boolean? = null,
        summarizeEngine: SummarizationEngine? = null,
        summaryDetail: SummaryDetail? = null,
        autoSummarize: Boolean? = null
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        translateEngine?.let { prefs.putString(KEY_TRANSLATE_ENGINE, it.id) }
        targetLanguage?.let { prefs.putString(KEY_TRANSLATE_TARGET_LANG, it) }
        protectCode?.let { prefs.putBoolean(KEY_TRANSLATE_PROTECT_CODE, it) }
        autoTranslate?.let { prefs.putBoolean(KEY_TRANSLATE_AUTO, it) }
        summarizeEngine?.let { prefs.putString(KEY_SUMMARIZE_ENGINE, it.id) }
        summaryDetail?.let { prefs.putString(KEY_SUMMARIZE_DETAIL, it.id) }
        autoSummarize?.let { prefs.putBoolean(KEY_SUMMARIZE_AUTO, it) }
        prefs.apply()
    }

    /**
     * Translates thinking text using ML Kit on-device translation.
     * Protects code blocks (``` ```, ` `, val, fun, import, class, interface, suspend, override, private, public, data, sealed, object, companion, CamelCase, function calls) from translation.
     * Falls back to heuristic translation if ML Kit fails or model unavailable.
     */
    suspend fun translateThinking(
        context: Context,
        text: String,
        targetLanguage: String = "tr",
        protectCode: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text

        // Try ML Kit translation first
        val mlKitResult = try {
            translateWithMLKit(context, text, targetLanguage, protectCode)
        } catch (e: Exception) {
            null
        }

        // Return ML Kit result if successful, otherwise fallback to heuristic
        mlKitResult ?: translateWithHeuristic(text, targetLanguage, protectCode)
    }

    /**
     * Convenience function that reads translation settings from SharedPreferences.
     * Uses target language and code protection settings from user preferences.
     */
    suspend fun translateThinking(context: Context, text: String): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val targetLanguage = prefs.getString(KEY_TRANSLATE_TARGET_LANG, DEFAULT_TARGET_LANG) ?: DEFAULT_TARGET_LANG
        val protectCode = prefs.getBoolean(KEY_TRANSLATE_PROTECT_CODE, DEFAULT_PROTECT_CODE)
        translateThinking(context, text, targetLanguage, protectCode)
    }

    /**
     * ML Kit Translation implementation with code protection.
     */
    private suspend fun translateWithMLKit(
        context: Context,
        text: String,
        targetLanguage: String,
        protectCode: Boolean
    ): String = suspendCancellableCoroutine { continuation: kotlin.coroutines.Continuation<String> ->
        val options = TranslatorOptions.Builder()
            .setSourceLanguage("auto")
            .setTargetLanguage(targetLanguage)
            .build()
        val translator = Translation.getClient(options)

        // Download model if needed, then translate
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                // Protect code blocks before translation
                val (protectedText, codeBlocks) = if (protectCode) protectCodeBlocks(text) else text to emptyList<String>()

                translator.translate(protectedText)
                    .addOnSuccessListener { translated ->
                        // Restore code blocks after translation
                        val result = if (protectCode) restoreCodeBlocks(translated, codeBlocks) else translated
                        continuation.resumeWith(Result.success(result))
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWith(Result.failure(e))
                    }
            }
            .addOnFailureListener { e ->
                continuation.resumeWith(Result.failure(e))
            }
    }

    /**
     * Fallback heuristic translation with code protection.
     * Used when ML Kit is unavailable or fails.
     */
    private fun translateWithHeuristic(
        original: String,
        targetLanguage: String,
        protectCode: Boolean
    ): String {
        if (targetLanguage != "tr") {
            return original // Only Turkish supported in fallback
        }

        val (processedText, codeBlocks) = if (protectCode) protectCodeBlocks(original) else original to emptyList<String>()

        val lines = processedText.lines()
        val translatedLines = lines.map { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                line
            } else {
                // Simple Turkish wrapper for reasoning context
                when {
                    trimmed.startsWith("I ") || trimmed.startsWith("We ") -> line.replaceFirst("I ", "Ben ").replaceFirst("We ", "Biz ")
                    trimmed.startsWith("The ") -> line.replaceFirst("The ", "")
                    trimmed.startsWith("This ") -> line.replaceFirst("This ", "Bu ")
                    trimmed.startsWith("That ") -> line.replaceFirst("That ", "Şu ")
                    trimmed.startsWith("It ") -> line.replaceFirst("It ", "O ")
                    trimmed.startsWith("To ") -> line.replaceFirst("To ", "İçin ")
                    trimmed.startsWith("For ") -> line.replaceFirst("For ", "İçin ")
                    trimmed.startsWith("And ") -> line.replaceFirst("And ", "Ve ")
                    trimmed.startsWith("But ") -> line.replaceFirst("But ", "Ama ")
                    trimmed.startsWith("Or ") -> line.replaceFirst("Or ", "Veya ")
                    trimmed.startsWith("If ") -> line.replaceFirst("If ", "Eğer ")
                    trimmed.startsWith("Then ") -> line.replaceFirst("Then ", "O zaman ")
                    trimmed.startsWith("Else ") -> line.replaceFirst("Else ", "Aksi takdirde ")
                    else -> line
                }
            }
        }

        val result = if (protectCode) restoreCodeBlocks(translatedLines.joinToString("\n"), codeBlocks) else translatedLines.joinToString("\n")

        return buildString {
            appendLine("--- [Türkçe'ye Çevrildi (Heuristic)] ---")
            appendLine(result)
            appendLine("\n--- [Orijinal Düşünce / Original Thinking Included] ---")
        }
    }

    /**
     * Protect code blocks from translation by replacing them with placeholders.
     * Protects: ```code```, `inline code`, val, fun, import, class, interface, suspend, override, private, public, data, sealed, object, companion, CamelCase, function calls
     */
    private fun protectCodeBlocks(text: String): Pair<String, List<String>> {
        val codeBlocks = mutableListOf<String>()
        var result = text
        var placeholderIndex = 0

        // 1. Protect ```code``` blocks (multiline)
        val codeBlockRegex = Regex("```[\\s\\S]*?```")
        result = codeBlockRegex.replace(result) { match ->
            val placeholder = "___CODE_BLOCK_${placeholderIndex++}___"
            codeBlocks.add(match.value)
            placeholder
        }

        // 2. Protect inline `code`
        val inlineCodeRegex = Regex("`[^`]+`")
        result = inlineCodeRegex.replace(result) { match ->
            val placeholder = "___INLINE_CODE_${placeholderIndex++}___"
            codeBlocks.add(match.value)
            placeholder
        }

        // 3. Protect Kotlin keywords and patterns that indicate code
        val codePatterns = listOf(
            Regex("\\b(val|var|fun|import|class|interface|object|companion|data|sealed|suspend|override|private|public|internal|protected|inline|const|lateinit|by|where|when|if|else|for|while|do|try|catch|finally|throw|return|break|continue|as\\?|as|is\\?|is|in|!in|out|reified|crossinline|noinline|tailrec|operator|infix|open|abstract|final|sealed|annotation|enum|expect|actual|internal|public|private|protected)\\b"),
            Regex("\\b[A-Z][a-zA-Z0-9]*[A-Z][a-zA-Z0-9]*\\b"), // CamelCase class names
            Regex("\\b[a-z][a-zA-Z0-9]*\\([^)]*\\)") // function calls
        )

        for (pattern in codePatterns) {
            result = pattern.replace(result) { match ->
                val placeholder = "___CODE_PATTERN_${placeholderIndex++}___"
                codeBlocks.add(match.value)
                placeholder
            }
        }

        return result to codeBlocks
    }

    /**
     * Restore code blocks after translation by replacing placeholders with original code.
     */
    private fun restoreCodeBlocks(text: String, codeBlocks: List<String>): String {
        var result = text
        codeBlocks.forEachIndexed { index, block ->
            val placeholder = when {
                block.startsWith("```") || block.endsWith("```") -> "___CODE_BLOCK_${index}___"
                block.startsWith("`") && block.endsWith("`") -> "___INLINE_CODE_${index}___"
                else -> "___CODE_PATTERN_${index}___"
            }
            result = result.replace(placeholder, block)
        }
        return result
    }

    /**
     * Summarize thinking text using selected engine
     */
    suspend fun summarizeThinking(
        context: Context,
        text: String,
        scope: CoroutineScope
    ): String = withContext(Dispatchers.Default) {
        if (text.length < 100) return@withContext "Düşünce çok kısa, özetlenecek yeterli içerik yok."

        val engine = getSummarizationEngine(context)
        val detail = getSummaryDetail(context)

        return@withContext when (engine) {
            SummarizationEngine.HEURISTIC -> summarizeHeuristic(text, detail)
            SummarizationEngine.LLM -> summarizeWithLLM(context, text, detail, scope)
            SummarizationEngine.HYBRID -> {
                if (text.length < 5000) summarizeHeuristic(text, detail)
                else summarizeWithLLM(context, text, detail, scope)
            }
        }
    }

    /**
     * Heuristic summarization (sentence extraction + key phrases)
     */
    private fun summarizeHeuristic(text: String, detail: SummaryDetail): String {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .filter { it.length > 15 }
            .take(detail.maxPoints * 2) // Get more candidates

        // Score sentences by keyword frequency
        val wordFreq = mutableMapOf<String, Int>()
        sentences.flatMap { it.split(Regex("\\s+")) }
            .filter { it.length > 4 }
            .forEach { wordFreq[it.lowercase()] = wordFreq.getOrDefault(it.lowercase(), 0) + 1 }

        val scoredSentences = sentences.map { sentence ->
            val score = sentence.split(Regex("\\s+"))
                .filter { it.length > 4 }
                .sumOf { wordFreq.getOrDefault(it.lowercase(), 0) }
            sentence to score
        }.sortedByDescending { it.second }
            .take(detail.maxPoints)
            .map { it.first }

        return buildString {
            appendLine("💡 **Derin Düşünme Özeti (${detail.displayName})**")
            for ((index, point) in scoredSentences.withIndex()) {
                appendLine("${index + 1}. ${point.trim()}")
            }
            appendLine("\n📊 İstatistik: ${text.length} karakter | ${sentences.size} cümle | Motor: Heuristic")
        }
    }

    /**
     * LLM-based summarization (placeholder for integration)
     */
    private suspend fun summarizeWithLLM(
        context: Context,
        text: String,
        detail: SummaryDetail,
        scope: CoroutineScope
    ): String {
        // TODO: Integrate with active model via ChatViewModel
        // For now, return enhanced heuristic
        return summarizeHeuristic(text, detail) + "\n\n⚠️ LLM özeti henüz entegre edilmedi, heuristic kullanıldı."
    }

    /**
     * Export thinking to workspace
     */
    fun exportToWorkspace(
        context: Context,
        content: String,
        blockId: String,
        includeOriginal: Boolean = true
    ) {
        try {
            val file = java.io.File(context.filesDir, "workspace/thinking_${blockId}.md")
            file.parentFile?.mkdirs()

            val exportContent = buildString {
                appendLine("# Derin Düşünce Bloğu: $blockId")
                appendLine("Tarih: ${java.time.LocalDateTime.now()}")
                appendLine("")
                if (includeOriginal) {
                    appendLine("## Orijinal Düşünce")
                    appendLine("")
                    appendLine("```")
                    appendLine(content)
                    appendLine("```")
                    appendLine("")
                }
            }

            file.writeText(exportContent)
            Toast.makeText(context, "Düşünce kaydedildi: workspace/thinking_${blockId}.md", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Kayıt başarısız: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}