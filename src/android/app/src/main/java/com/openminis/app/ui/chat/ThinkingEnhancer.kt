package com.openminis.app.ui.chat

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    internal const val DEFAULT_TRANSLATE_ENGINE = TranslationEngine.ML_KIT
    internal const val DEFAULT_TARGET_LANG = "tr"
    internal const val DEFAULT_PROTECT_CODE = true
    internal const val DEFAULT_TRANSLATE_AUTO = false
    internal const val DEFAULT_SUMMARIZE_ENGINE = SummarizationEngine.HYBRID
    internal const val DEFAULT_SUMMARY_DETAIL = SummaryDetail.BRIEF
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
     * Translate thinking text using selected engine
     */
    suspend fun translateThinking(
        context: Context,
        text: String,
        scope: CoroutineScope
    ): String = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext text

        val engine = getTranslationEngine(context)
        val targetLang = getTargetLanguage(context)
        val protectCode = isCodeProtectionEnabled(context)

        return@withContext when (engine) {
            TranslationEngine.ML_KIT -> translateWithMLKit(text, targetLang, protectCode)
            TranslationEngine.LLM -> translateWithLLM(context, text, targetLang, protectCode, scope)
            TranslationEngine.HYBRID -> {
                val mlKitResult = translateWithMLKit(text, targetLang, protectCode)
                if (mlKitResult == text || mlKitResult.isBlank()) {
                    translateWithLLM(context, text, targetLang, protectCode, scope)
                } else {
                    mlKitResult
                }
            }
        }
    }

    /**
     * ML Kit Translation (On-Device)
     */
    private suspend fun translateWithMLKit(
        text: String,
        targetLang: String,
        protectCode: Boolean
    ): String = withContext(Dispatchers.IO) {
        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(com.google.mlkit.nl.languageid.LanguageIdentification.UNDETERMINED)
                .setTargetLanguage(targetLang)
                .build()
            val translator = Translation.getClient(options)

            // Download model if needed
            val downloadResult = translator.downloadModelIfNeeded().await()
            if (!downloadResult) {
                return@withContext buildFallbackTranslation(text, targetLang, "ML Kit model indirilemedi")
            }

            // Protect code blocks if needed
            val (protectedText, codeBlocks) = if (protectCode) protectCodeBlocks(text) else text to emptyList<String>()

            val translated = translator.translate(protectedText).await()

            // Restore code blocks
            restoreCodeBlocks(translated, codeBlocks)
        } catch (e: Exception) {
            buildFallbackTranslation(text, targetLang, "ML Kit hatası: ${e.message}")
        }
    }

    /**
     * LLM-based Translation (using active model via ChatViewModel)
     */
    private suspend fun translateWithLLM(
        context: Context,
        text: String,
        targetLang: String,
        protectCode: Boolean,
        scope: CoroutineScope
    ): String {
        // This would integrate with the active model via ChatViewModel
        // For now, return enhanced heuristic as fallback
        return buildFallbackTranslation(text, targetLang, "LLM çevirisi henüz entegre edilmedi")
    }

    /**
     * Fallback heuristic translation with code protection
     */
    private fun buildFallbackTranslation(original: String, targetLang: String, note: String): String {
        if (targetLang != "tr") {
            return original // Only Turkish supported in fallback
        }

        val lines = original.lines()
        val processed = lines.map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("import ") ||
                trimmed.contains("val ") || trimmed.contains("fun ") ||
                trimmed.contains("class ") || trimmed.contains("interface ") ||
                trimmed.contains("private ") || trimmed.contains("public ") ||
                trimmed.contains("suspend ") || trimmed.contains("override ")
            ) {
                line // Keep code intact
            } else if (trimmed.isNotEmpty()) {
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
            } else {
                line
            }
        }

        buildString {
            appendLine("--- [Türkçe'ye Çevrildi - $note] ---")
            appendLine(processed.joinToString("\n"))
            appendLine("\n--- [Orijinal Düşünce] ---")
        }
    }

    /**
     * Protect code blocks from translation
     */
    private fun protectCodeBlocks(text: String): Pair<String, List<String>> {
        val codeBlocks = mutableListOf<String>()
        var result = text
        var placeholderIndex = 0

        // Protect ```code``` blocks
        val codeBlockRegex = Regex("```[\\s\\S]*?```")
        result = codeBlockRegex.replace(result) { match ->
            val placeholder = "___CODE_BLOCK_${placeholderIndex++}___"
            codeBlocks.add(match.value)
            placeholder
        }

        // Protect inline `code`
        val inlineCodeRegex = Regex("`[^`]+`")
        result = inlineCodeRegex.replace(result) { match ->
            val placeholder = "___INLINE_CODE_${placeholderIndex++}___"
            codeBlocks.add(match.value)
            placeholder
        }

        return result to codeBlocks
    }

    /**
     * Restore code blocks after translation
     */
    private fun restoreCodeBlocks(text: String, codeBlocks: List<String>): String {
        var result = text
        codeBlocks.forEachIndexed { index, block ->
            val placeholder = if (block.startsWith("```")) "___CODE_BLOCK_${index}___" else "___INLINE_CODE_${index}___"
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

        buildString {
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

/**
 * Settings Screen for Thinking Enhancements
 * Add to Settings > Agent Runtime > Düşünce Geliştirmeleri
 */
class ThinkingEnhancementsSettingsScreen(
    private val context: Context,
    onBack: () -> Unit
) {
    // This would be a @Composable function in the actual implementation
    // Shows all the translation/summarization options
    companion object {
        @Composable
        fun Screen(
            context: Context = androidx.compose.ui.platform.LocalContext.current,
            onBack: () -> Unit
        ) {
            val prefs = context.getSharedPreferences(ThinkingEnhancer.PREFS_NAME, Context.MODE_PRIVATE)

            var translateEngine by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(
                    ThinkingEnhancer.TranslationEngine.values().firstOrNull { it.id == prefs.getString(ThinkingEnhancer.KEY_TRANSLATE_ENGINE, "") } ?: ThinkingEnhancer.TranslationEngine.ML_KIT
                )
            }
            var targetLang by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(prefs.getString(ThinkingEnhancer.KEY_TRANSLATE_TARGET_LANG, "tr"))
            }
            var protectCode by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(prefs.getBoolean(ThinkingEnhancer.KEY_TRANSLATE_PROTECT_CODE, true))
            }
            var autoTranslate by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(prefs.getBoolean(ThinkingEnhancer.KEY_TRANSLATE_AUTO, false))
            }
            var summarizeEngine by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(
                    ThinkingEnhancer.SummarizationEngine.values().firstOrNull { it.id == prefs.getString(ThinkingEnhancer.KEY_SUMMARIZE_ENGINE, "") } ?: ThinkingEnhancer.SummarizationEngine.HYBRID
                )
            }
            var summaryDetail by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(
                    ThinkingEnhancer.SummaryDetail.values().firstOrNull { it.id == prefs.getString(ThinkingEnhancer.KEY_SUMMARIZE_DETAIL, "") } ?: ThinkingEnhancer.SummaryDetail.BRIEF
                )
            }
            var autoSummarize by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(prefs.getBoolean(ThinkingEnhancer.KEY_SUMMARIZE_AUTO, false))
            }

            androidx.compose.material3.Scaffold(
                topBar = {
                    androidx.compose.material3.TopAppBar(
                        title = { androidx.compose.material3.Text("Düşünce Geliştirmeleri") },
                        navigationIcon = {
                            androidx.compose.material3.IconButton(onClick = onBack) {
                                androidx.compose.material.icons.filled.ArrowBack()
                            }
                        }
                    )
                }
            ) { padding ->
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    // Translation Section
                    androidx.compose.material3.Text(
                        text = "🌐 Çeviri Ayarları",
                        fontSize = 18.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
                    )

                    // Engine Selection
                    ThinkingEnhancer.TranslationEngine.values().forEach { engine ->
                        androidx.compose.material3.ListItem(
                            headlineContent = {
                                androidx.compose.material3.Text(engine.displayName)
                            },
                            trailingContent = {
                                if (translateEngine == engine) androidx.compose.material.icons.filled.Check()
                            },
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .clickable { translateEngine = engine },
                            secondaryContent = {
                                when (engine) {
                                    ThinkingEnhancer.TranslationEngine.ML_KIT -> androidx.compose.material3.Text("Çevrimdışı, hızlı, gizli", fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                                    ThinkingEnhancer.TranslationEngine.LLM -> androidx.compose.material3.Text("Bağlamlı, token maliyetli", fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                                    ThinkingEnhancer.TranslationEngine.HYBRID -> androidx.compose.material3.Text("ML Kit + LLM yedek", fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        )
                    }

                    androidx.compose.material3.Divider(modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                    // Target Language
                    androidx.compose.material3.ListItem(
                        headlineContent = { androidx.compose.material3.Text("Hedef Dil") },
                        trailingContent = {
                            androidx.compose.material3.Text(targetLang, color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                        },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    )

                    // Protect Code
                    androidx.compose.material3.ListItem(
                        headlineContent = { androidx.compose.material3.Text("Kod Bloklarını Koru") },
                        trailingContent = {
                            androidx.compose.material3.Switch(
                                checked = protectCode,
                                onCheckedChange = { protectCode = it }
                            )
                        },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    )

                    // Auto Translate
                    androidx.compose.material3.ListItem(
                        headlineContent = { androidx.compose.material3.Text("Otomatik Çevir (Streaming bitince)") },
                        trailingContent = {
                            androidx.compose.material3.Switch(
                                checked = autoTranslate,
                                onCheckedChange = { autoTranslate = it }
                            )
                        },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    )

                    androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))

                    // Summarization Section
                    androidx.compose.material3.Text(
                        text = "📝 Özetleme Ayarları",
                        fontSize = 18.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
                    )

                    ThinkingEnhancer.SummarizationEngine.values().forEach { engine ->
                        androidx.compose.material3.ListItem(
                            headlineContent = { androidx.compose.material3.Text(engine.displayName) },
                            trailingContent = {
                                if (summarizeEngine == engine) androidx.compose.material.icons.filled.Check()
                            },
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .clickable { summarizeEngine = engine },
                            secondaryContent = {
                                when (engine) {
                                    ThinkingEnhancer.SummarizationEngine.HEURISTIC -> androidx.compose.material3.Text("Hızlı, basit", fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                                    ThinkingEnhancer.SummarizationEngine.LLM -> androidx.compose.material3.Text("Anlamsal, bağlamlı", fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                                    ThinkingEnhancer.SummarizationEngine.HYBRID -> androidx.compose.material3.Text("Akıllı seçim", fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        )
                    }

                    androidx.compose.material3.Divider(modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                    // Detail Level
                    androidx.compose.material3.Text(
                        text = "Detay Seviyesi: ${summaryDetail.displayName}",
                        fontSize = 14.sp,
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    ThinkingEnhancer.SummaryDetail.values().forEach { detail ->
                        androidx.compose.material3.ListItem(
                            headlineContent = { androidx.compose.material3.Text(detail.displayName) },
                            trailingContent = {
                                if (summaryDetail == detail) androidx.compose.material.icons.filled.Check()
                            },
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .clickable { summaryDetail = detail }
                        )
                    }

                    // Auto Summarize
                    androidx.compose.material3.ListItem(
                        headlineContent = { androidx.compose.material3.Text("Otomatik Özetle") },
                        trailingContent = {
                            androidx.compose.material3.Switch(
                                checked = autoSummarize,
                                onCheckedChange = { autoSummarize = it }
                            )
                        },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    )

                    androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))

                    // Save Button
                    androidx.compose.material3.Button(
                        onClick = {
                            ThinkingEnhancer.savePreferences(
                                context = context,
                                translateEngine = translateEngine,
                                targetLanguage = targetLang,
                                protectCode = protectCode,
                                autoTranslate = autoTranslate,
                                summarizeEngine = summarizeEngine,
                                summaryDetail = summaryDetail,
                                autoSummarize = autoSummarize
                            )
                            android.widget.Toast.makeText(context, "Kaydedildi", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        androidx.compose.material3.Text("Kaydet ve Uygula")
                    }
                }
            }
        }
    }
}