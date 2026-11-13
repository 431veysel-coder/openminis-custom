package com.openminis.app.data

import android.content.Context
import java.util.Locale

/**
 * [MINIS-OZEL] Settings for translating a reasoning ("thinking") block.
 *
 * Kept in its own SharedPreferences file rather than the config registry: the
 * config registry is a mirrored iOS schema, and these keys exist only in this
 * fork. A separate file also means a future upstream sync of the config schema
 * cannot collide with them.
 *
 * Deliberately NO translation model is bundled by default. Translation is an
 * explicit, per-block user action, and picking a model the user did not choose
 * would spend their quota silently. [effectiveModelEntryId] therefore only
 * falls back to the models the user already nominated for the agent loop.
 */
object TranslationPrefs {

    private const val PREFS = "thinking_translation_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGET_LANG = "target_language"
    private const val KEY_MODEL_ENTRY = "model_entry_id"
    private const val KEY_AUTO = "auto_translate"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Master switch: whether the translate button appears on thinking blocks. */
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Target language for the translation, as a display name ("Turkish").
     * Empty means "follow the app's UI language".
     */
    fun targetLanguage(ctx: Context): String =
        prefs(ctx).getString(KEY_TARGET_LANG, "").orEmpty()

    fun setTargetLanguage(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_TARGET_LANG, value.trim()).apply()

    /** The model entry the user pinned for translation, or "" for the fallback. */
    fun modelEntryId(ctx: Context): String =
        prefs(ctx).getString(KEY_MODEL_ENTRY, "").orEmpty()

    fun setModelEntryId(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_MODEL_ENTRY, value).apply()

    /** Whether a thinking block is translated as soon as it is expanded. */
    fun autoTranslate(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO, false)

    fun setAutoTranslate(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO, value).apply()

    /**
     * The language the prompt asks for. Falls back to the device locale's
     * display name in English, which is what the model expects ("Turkish",
     * "German"), rather than the raw tag.
     */
    fun resolvedTargetLanguage(ctx: Context): String {
        val pinned = targetLanguage(ctx)
        if (pinned.isNotBlank()) return pinned
        return runCatching {
            Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "English"
    }

    /**
     * Which model entry to translate with: the user's pin if it still exists,
     * else the first model they nominated for the agent loop. Null when
     * neither is available — the button then explains that a model must be
     * picked instead of silently spending an unconfigured model's quota.
     */
    fun effectiveModelEntryId(ctx: Context, available: List<String>, loopEntries: List<String>): String? {
        val pinned = modelEntryId(ctx)
        if (pinned.isNotBlank() && pinned in available) return pinned
        return loopEntries.firstOrNull { it in available }
    }
}
