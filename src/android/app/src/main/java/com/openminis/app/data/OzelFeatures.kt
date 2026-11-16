package com.openminis.app.data

import android.content.Context

/**
 * [MINIS-OZEL] Per-feature switches for everything this fork added, in three
 * tiers.
 *
 * ## Why tiers and not one list
 *
 * "We added it" is not one kind of claim. Three very different things end up
 * in a fork like this:
 *
 *  - **STABLE** — finished, tested, on by default. A user who never opens
 *    Settings should get all of it. Turning one off is a preference, not a
 *    safety measure.
 *  - **BETA** — works, but its seams show: an edge case here, an unfinished
 *    corner there. Shipped on purpose so people can use it, but the user has to
 *    be told what they are taking on. Off by default.
 *  - **EXPERIMENTAL** — an idea that may be withdrawn. It may open a socket, it
 *    may change behaviour on every launch, it may not survive the next release.
 *    Off by default, and the screen says so in as many words.
 *
 * Collapsing those into a single on/off list would force one of two bad
 * answers: hide the beta work until it is perfect (so nobody ever finds the
 * bugs), or ship it silently on (so a surprise lands on someone who did not ask
 * for it).
 *
 * ## What a switch may and may not do
 *
 * A flag hides an ENTRY POINT or stops a BACKGROUND behaviour. It never deletes
 * user data and never makes saved state unreadable:
 *
 *  - turning Live Tasks off hides the row; a task already running keeps
 *    running, and turning it back on shows it again;
 *  - turning Toolchains off hides the screen; installed packages stay on disk;
 *  - turning the Evren preset off removes it from Add Provider; an instance the
 *    user already created keeps working, because it is an ordinary OpenAI
 *    provider by then (same rule as [com.openminis.app.util.CopilotFeatureFlag]);
 *  - turning the developer server off closes the socket; nothing else depends
 *    on it.
 *
 * ## Why SharedPreferences and not the config registry
 *
 * The config registry is a schema mirrored with iOS, where none of these keys
 * exist. A separate file means an upstream sync of that schema cannot collide
 * with them — the same reasoning [TranslationPrefs] documents.
 */
object OzelFeatures {

    private const val PREFS = "ozel_features_prefs"

    /** How much trust a feature has earned. Drives the default and the wording. */
    enum class Tier {
        /** Finished, default on. A switch here is a preference. */
        STABLE,

        /** Usable but imperfect, default off. The user is told what may break. */
        BETA,

        /**
         * Might be withdrawn, default off. May change behaviour on every launch
         * or open a local socket; kept behind a switch so it is never a surprise.
         */
        EXPERIMENTAL,
        ;

        val isGated: Boolean get() = this != STABLE
    }

    /** One toggle as the settings screen renders it. */
    enum class Feature(
        val key: String,
        val tier: Tier,
        val defaultEnabled: Boolean,
        /** One line shown under a gated feature. Empty for stable. */
        val caveat: String = "",
    ) {
        /** Settings › Live Tasks: what is running right now. */
        LIVE_TASKS("live_tasks", Tier.STABLE, true),

        /** Settings › Toolchains: install build tools into the sandbox. */
        TOOLCHAINS("toolchains", Tier.STABLE, true),

        /** Thinking-block translation (mirrors, but does not replace, TranslationPrefs.enabled). */
        TRANSLATION("translation", Tier.STABLE, true),

        /** The Evren API entry in Add Provider. */
        EVREN_PRESET("evren_preset", Tier.STABLE, true),

        /** The Quick Settings tile that opens a new chat. */
        QS_TILE("qs_tile", Tier.STABLE, true),

        /** Settings › Background gates: permission checklist for long tasks. */
        BACKGROUND_GATES("background_gates", Tier.STABLE, true),

        /**
         * Translation packs: downloadable terminology.
         *
         * BETA because the pack format is version 1 and a pack written for it
         * may need re-shaping once more than one language is in use. Nothing is
         * lost by trying it — the pack is a file, removable in one tap — but a
         * translation is a paid request, so the user should choose to opt in.
         */
        TRANSLATION_PACKS(
            "translation_packs",
            Tier.BETA,
            false,
            "Pack format is v1; it may change shape as more languages land.",
        ),

        /**
         * The on-device developer server (127.0.0.1:5321).
         *
         * EXPERIMENTAL, not merely beta: its surface can read stored provider
         * keys, take screenshots and drive the UI. It is loopback-only and
         * token-gated, and it closes itself after 30 minutes, but a feature
         * whose "on" state is a listening socket is never "stable" in an app
         * that ships to someone else's phone.
         */
        DEV_SERVER(
            "dev_server",
            Tier.EXPERIMENTAL,
            false,
            "Opens a local API that can read your provider keys and drive the interface.",
        ),
        ;

        companion object {
            fun parse(raw: String?): Feature? = entries.firstOrNull { it.key == raw?.trim() }

            fun of(tier: Tier): List<Feature> = entries.filter { it.tier == tier }
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
     * Whether the developer server should run.
     *
     * Requires BOTH the per-feature switch and the build's DEV_TOOLS: the switch
     * says the user wants it, DEV_TOOLS says this APK has the code at all.
     */
    fun devServerEnabled(ctx: Context): Boolean =
        isEnabled(ctx, Feature.DEV_SERVER)

    /** How many gated features the user has turned on. Shown in the screen header. */
    fun enabledGatedCount(ctx: Context): Int =
        Feature.entries.count { it.tier.isGated && isEnabled(ctx, it) }
}
