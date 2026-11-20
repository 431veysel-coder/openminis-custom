package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * [T-android-session-grouping] A session group. Code says "Folder", the UI says
 * "Group" — the same deliberate split iOS uses, because `ModelGroup` (LLM
 * fallback routing) already owns the word "Group" at symbol level.
 *
 * Mirrors the iOS `folders` table (ChatStore.swift:795) column-for-column so a
 * future sync layer can map the two without a translation step.
 *
 * ## Why `name` is NOT unique
 *
 * Two devices can each create "Work" offline; both are kept, as two rows with
 * different UUIDs. Keying on the name instead would turn a rename from a
 * one-field edit into an identity change — delete the old key, create a new
 * one, migrate every member — which tears across devices: A renames while B
 * files under the old name, and B's sessions end up pointing at a key that no
 * longer exists. A locally-generated UUID needs no cross-device coordination,
 * which is exactly the property offline creation requires.
 *
 * Consequence: every name-based lookup must tolerate multiple matches.
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Optional icon token. Null = use the composed top-3-category glyphs. */
    val icon: String? = null,
    /** Optional theme color token. */
    val color: String? = null,
    /**
     * `"manual"` | `"ai"`. Provenance only — nothing branches on it. Kept so
     * an AI-suggested group stays distinguishable for future UI/telemetry.
     */
    val origin: String = ORIGIN_MANUAL,
    /**
     * Reserved for drag-reorder. Always 0 today; no code path writes it. Kept
     * as a column so the record shape matches iOS and a later reorder feature
     * needs no migration.
     */
    @ColumnInfo(name = "sort_index") val sortIndex: Int = 0,
    /** Non-null = this group floats above unpinned groups. Milliseconds. */
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,
    /**
     * One-sentence description, capped at [DESC_MAX_CHARS]. Never rendered in
     * the session list — it is the group picker's row subtitle, and context for
     * future auto-grouping.
     */
    val description: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /**
     * Tracks RECORD edits (rename / pin / icon) — NOT member activity. A
     * session moving in or out of the group must not touch it.
     */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,

    // ── [MINIS-OZEL 37071] project fields ─────────────────────────────────
    // Every one of these has a default and every migration column is
    // NOT NULL DEFAULT-able, so MIGRATION_14_15 rewrites no rows and a
    // downgrade only loses the feature (see MIGRATION_15_14).

    /**
     * `"project"` (owns a shared area + memory) or `"session"` (a label only,
     * exactly what every group was before 37071).
     *
     * Defaults to `"project"`: the user asked for group memory, and existing
     * groups must not keep behaving as labels. A group whose owner wants the
     * old behaviour flips this one field.
     */
    @ColumnInfo(name = "space_scope") val spaceScope: String = SCOPE_PROJECT,

    /**
     * Whether this project's memory (PROJECT.md + daily logs) is injected into
     * its chats' system prompts. 1/0 rather than Boolean to match
     * `sessions.memory_enabled`, so the two read the same way in SQL.
     * A per-chat toggle still exists and can only switch memory OFF.
     */
    @ColumnInfo(name = "memory_enabled") val memoryEnabled: Int = 1,

    /**
     * One-to-two sentence steer for every chat in this project ("answer in
     * Turkish, keep it short", "this repo is GPL, never add a licence header").
     * Injected above the project memory, separate from the user's SOUL.md:
     * that one is personal, this one is work context.
     */
    @ColumnInfo(name = "memory_prompt") val memoryPrompt: String? = null,

    /** Pre-selected model for new chats created inside this project. */
    @ColumnInfo(name = "default_model_id") val defaultModelId: String? = null,

    /** Pre-selected team (TeamStore id) for new chats / `team_run` here. */
    @ColumnInfo(name = "default_team_id") val defaultTeamId: String? = null,

    /**
     * Non-null = archived: its chats drop out of the session list and its
     * memory/`@`-mentions stop resolving, but nothing on disk is touched.
     * That is the difference between "archive" (recoverable) and delete.
     */
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
) {
    val isPinned: Boolean get() = pinnedAt != null

    /** True when this group actually owns a shared area (workspace + memory). */
    val isProject: Boolean get() = spaceScope == SCOPE_PROJECT

    val isArchived: Boolean get() = archivedAt != null


    companion object {
        const val ORIGIN_MANUAL = "manual"
        const val ORIGIN_AI = "ai"
        /**
         * [MINIS-OZEL 37072] The one-time "everything before projects" group
         * (§10.0 of docs/ozel/PROJE-HAFIZA.md). Provenance only: the group can
         * be renamed or emptied, and re-running the migration recognises its own
         * work by this marker plus the pref flag, never by the name.
         */
        const val ORIGIN_MIGRATION = "migration"

        /** iOS caps the description at 100 chars; keep the two in step. */
        const val DESC_MAX_CHARS = 100

        /**
         * [MINIS-OZEL 37071] A group either OWNS a shared area or is just a
         * label. Both are legitimate: a user who only wants to keep chats tidy
         * should not silently get a second memory and a second workspace.
         */
        const val SCOPE_PROJECT = "project"
        const val SCOPE_SESSION = "session"
    }
}
