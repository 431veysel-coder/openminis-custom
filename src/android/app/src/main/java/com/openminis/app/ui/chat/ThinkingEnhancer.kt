package com.openminis.app.ui.chat

import android.content.Context
import android.widget.Toast
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.Result
import kotlin.coroutines.suspendCoroutine

/**
 * ThinkingEnhancer: Provides advanced capabilities for deep thinking blocks:
 * - ML Kit Translation (on-device, offline)
 * - Key points extraction / summarization
 * - Markdown export to workspace
 */
object ThinkingEnhancer {

    // SharedPreferences keys for translation settings
    private const val PREFS_NAME = "thinking_enhancer_prefs"
    private const val KEY_TRANSLATE_TARGET_LANG = "translate_target_lang"
    private const val KEY_TRANSLATE_PROTECT_CODE = "translate_protect_code"
    private const val DEFAULT_TARGET_LANG = "tr"
    private const val DEFAULT_PROTECT_CODE = true

    /**
     * Translates thinking text using ML Kit on-device translation.
     * Protects code blocks (``` ```, ` `, val, fun, import, class, interface, etc.) from translation.
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
    ): String = suspendCoroutine { continuation ->
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
                        continuation.resume(result)
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWithException(e)
                    }
            }
            .addOnFailureListener { e ->
                continuation.resumeWithException(e)
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

        buildString {
            appendLine("--- [Türkçe'ye Çevrildi (Heuristic)] ---")
            appendLine(result)
            appendLine("\n--- [Orijinal Düşünce / Original Thinking Included] ---")
        }
    }

    /**
     * Protect code blocks from translation by replacing them with placeholders.
     * Protects: ```code```, `inline code`, val, fun, import, class, interface, suspend, override, private, public, data, sealed, object, companion
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
     * Extracts key points / summary from the thinking text.
     */
    suspend fun summarizeThinking(text: String): String = withContext(Dispatchers.Default) {
        if (text.length < 100) return@withContext "Düşünce çok kısa, özetlenecek bir şey yok."

        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val summaryPoints = sentences.filter { it.length > 20 }.take(3)

        buildString {
            appendLine("💡 **Derin Düşünme Öngörüleri (Key Insights):**")
            for ((index, point) in summaryPoints.withIndex()) {
                appendLine("${index + 1}. ${point.trim()}")
            }
            appendLine("\nToplam Karakter: ${text.length} | Cümle Sayısı: ${sentences.size}")
        }
    }

    /**
     * Exports thinking content to workspace file.
     */
    fun exportToWorkspace(context: Context, content: String, blockId: String) {
        try {
            val file = java.io.File(context.filesDir, "workspace/thinking_${blockId}.md")
            file.parentFile?.mkdirs()
            file.writeText(content)
            Toast.makeText(context, "Düşünce dosyaya kaydedildi: workspace/thinking_${blockId}.md", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Kayıt başarısız: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}