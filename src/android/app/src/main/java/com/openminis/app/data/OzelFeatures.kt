package com.openminis.app.data

import android.content.Context

/**
 * [MINIS-OZEL] Per-feature switches for everything this fork added.
 *
 * ## Why a flag registry rather than a switch on each screen
 *
 * Each fork feature already had its own switch somewhere, or none at all. That
 * is fine while every feature is new and wanted, and wrong the moment one of
 * them gets in the way: a user who does not want a Live Tasks row in Settings
 * had no way to remove it, and a user who does not trust the Evren preset had
 * no way to stop it appearing in Add Provider. "We added it" is not a reason
 * for it to be permanent in someone else's app.
 *
 * ## What a flag may and may not do
 *
 * A flag here hides an ENTRY POINT or stops a BACKGROUND behaviour. It never
 * deletes user data and never makes saved state unreadable:
 *
 *  - turning Live Tasks off hides the row; a task already running keeps
 *    running, and turning it back on shows it again;
 *  - turning Toolchains off hides the screen; installed packages stay on disk;
 *  - turning the Evren preset off removes it from Add Provider; an instance
 *    the user already created keeps working, because it is an ordinary OpenAI
 *    provider by then (same rule as [com.openminis.app.util.CopilotFeatureFlag]);
 *  - turning the developer server off closes the socket; nothing else depends
 *    on it.
 *
 * ## Why SharedPreferences and not the config registry
 *
 * The config registry is a schema mirrored with iOS, where none of these keys
 * exist. A separate file means an upstream sync of that schema cannot collide
 * with them — the same reasoning [TranslationPrefs] documents.
 *
 * ## Defaults
 *
 * Everything that is user-visible and harmless defaults ON, so an existing
 * install looks unchanged after updating. The developer server defaults OFF:
 * it is a local HTTP surface that can read provider keys and drive the UI, and
 * a feature that is off unless asked for cannot be reached by an app on the
 * same device that the user never consented to.
 */
object OzelFeatures {

    private const val PREFS = "ozel_features_prefs"

    /** One toggle as the settings screen renders it. */
    enum class Feature(
        val key: String,
        val defaultEnabled: Boolean,
    ) {
        /** Settings › Live Tasks: what is running right now. */
        LIVE_TASKS("live_tasks", true),

        /** Settings › Toolchains: install build tools into the sandbox. */
        TOOLCHAINS("toolchains", true),

        /** Thinking-block translation (mirrors, but does not replace, TranslationPrefs.enabled). */
        TRANSLATION("translation", true),

        /** The Evren API entry in Add Provider. */
        EVREN_PRESET("evren_preset", true),

        /** The Quick Settings tile that opens a new chat. */
        QS_TILE("qs_tile", true),

        /** Settings › Background gates: permission checklist for long tasks. */
        BACKGROUND_GATES("background_gates", true),

        /**
         * The on-device developer server (127.0.0.1:5321). Off by default.
         * Only meaningful in builds that ship it (DEV_TOOLS).
         */
        DEV_SERVER("dev_server", false),

        /**
         * [MINIS-OZEL 11.1] "Test connection" on a provider's detail screen.
         * One GET against the vendor's models endpoint, with the reason a
         * failure happened instead of an empty model list.
         */
        PROVIDER_TEST("provider_test", true),

        /**
         * [MINIS-OZEL 11.1] The notes module: the agent's scratchpad and the
         * user's vault, behind one Settings entry. Off hides the entry and
         * unregisters the agent tool; stored notes stay on disk (same rule as
         * every other flag here).
         */
        NOTES("notes", true),

        /**
         * [MINIS-OZEL 11.1] The vault inside Notes — entries only the user can
         * write. Its own flag because it is the one part of Notes that holds
         * secrets: someone may want the agent's notepad and not a keystore.
         */
        VAULT("vault", true),

        /**
         * [MINIS-OZEL 11.1] Whether an agent may READ a vault value.
         *
         * Off by default, and deliberately separate from [VAULT]: the vault
         * existing and the agent being allowed to see inside it are different
         * decisions. Turning this on means the secret is sent to the model
         * provider as part of the conversation — the settings row says so.
         */
        VAULT_AGENT_READ("vault_agent_read", false),

        /**
         * [MINIS-OZEL 11.1] The Station: several providers/models working the
         * same task side by side, each with its own terminal and browser tabs.
         */
        STATION("station", true),

        /**
         * [MINIS-OZEL 11.1] Edit mode: a floating pen that lets the user pin a
         * note onto whatever is on screen; the note lands in Notes for the
         * agent to act on. Default OFF — a persistent overlay on top of every
         * screen is intrusive unless the user asks for it.
         */
        EDIT_MODE("edit_mode", false),
        ;

        companion object {
            fun parse(raw: String?): Feature? = entries.firstOrNull { it.key == raw?.trim() }
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context, feature: Feature): Boolean =
        prefs(ctx).getBoolean(feature.key, feature.defaultEnabled)

    fun setEnabled(ctx: Context, feature: Feature, value: Boolean) {
        prefs(ctx).edit().putBoolean(feature.key, value).apply()
    }

    /** Every feature and its current value, in display order. */
    fun snapshot(ctx: Context): List<Pair<Feature, Boolean>> =
        Feature.entries.map { it to isEnabled(ctx, it) }

    /** Puts every switch back to its shipped default. */
    fun resetToDefaults(ctx: Context) {
        val editor = prefs(ctx).edit()
        Feature.entries.forEach { editor.putBoolean(it.key, it.defaultEnabled) }
        editor.apply()
    }

    /**
     * Whether the developer server should run. It also needs the build to have
     * been compiled with DEV_TOOLS; this only says the user wants it.
     */
    fun devServerEnabled(ctx: Context): Boolean = isEnabled(ctx, Feature.DEV_SERVER)
}
