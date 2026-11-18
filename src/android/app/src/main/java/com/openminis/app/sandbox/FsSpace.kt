package com.openminis.app.sandbox

import java.io.File

/**
 * [MINIS-OZEL 37070] The ONE place a *space key* becomes a host directory.
 *
 * ## Why this exists
 *
 * Until now `fsSessionId` was always a chat session id, and every caller that
 * needed a directory wrote `File(filesDir, "minis-sessions/$fsSessionId/$sub")`
 * by hand. With projects (a "folder" owning its own workspace, attachments,
 * offloads and browser tabs) the same key can name either a chat session OR a
 * project, and there are now several writers. Hand-rolled string building in
 * seven files is exactly how a project's agent ends up reading another
 * project's files: one forgotten call site silently keeps the old meaning.
 *
 * So: every host-path derivation goes through [baseDir] / [subDir], and a
 * single test (`FsSpaceTest`) plus a `rg` gate in the compliance audit keep
 * new raw `"minis-sessions/$…"` concatenations from creeping back in.
 *
 * ## Key shape
 *
 * ```
 * "<uuid>"                        → chat session   <filesDir>/minis-sessions/<uuid>/
 * "project:<folderId>"            → project space  <filesDir>/minis-spaces/<folderId>/
 * "sp_<runId8>_<member>"          → council member <filesDir>/minis-sessions/sp_…/  (unchanged)
 * ```
 *
 * A session id is a bare UUID (`ChatRepository` always uses
 * `UUID.randomUUID()`), so no chat can ever be named `project:…` — the prefix
 * cannot collide with existing data, and an existing device upgrades with zero
 * migration of directory names.
 *
 * ## Why a separate root for projects
 *
 * `OrphanedSessionFiles` walks `minis-sessions/<id>/` and deletes directories
 * whose id has no `sessions` row. A project directory living under that root
 * would look exactly like an orphan the moment its sessions were deleted, and
 * the cleanup sweep would take the user's workspace with it. `minis-spaces/`
 * is outside that walk by construction — the orphan sweep has to opt in
 * explicitly (and only reports, see `OrphanedSpaces`), which is the safe
 * direction for a delete.
 *
 * ## Why the key stays a `String`
 *
 * A typed wrapper (`sealed class SpaceKey`) would be nicer, but the value
 * travels through PRoot argv, shell-pool map keys, offload stubs and the
 * terminal's `MINIS_CHAT_SESSION_ID`, and `ExecutionCoordinator` compares it
 * with `!=` in several places. Retyping that is a large, purely mechanical
 * change with real regression risk and no user-visible payoff; a single
 * resolver plus tests buys most of the safety for a fraction of the blast
 * radius. The trade-off is explicit: the compiler will not catch a caller that
 * forgets to translate a project id into a space key, so `spaceKey()` below is
 * the only sanctioned translation and it is called exactly once per session,
 * at the point the session's project is known.
 */
object FsSpace {

    /** Prefix that marks a space key as a project's shared area. */
    const val PROJECT_PREFIX = "project:"

    /** Root directory name under `filesDir` for project spaces. */
    const val SPACES_ROOT = "minis-spaces"

    /** Root directory name under `filesDir` for chat session spaces. */
    const val SESSIONS_ROOT = "minis-sessions"

    /** True when [key] names a project space rather than a chat session. */
    fun isProject(key: String): Boolean = key.startsWith(PROJECT_PREFIX)

    /**
     * Translate a project (folder) id into a space key.
     *
     * `null` — a session that belongs to no project — returns `null` so callers
     * keep using the session id and today's behaviour is preserved byte for
     * byte. An already-prefixed input is returned unchanged, which makes the
     * function idempotent: re-stamping a space key (member config, forked
     * session) cannot produce `project:project:…`.
     */
    fun projectKey(folderId: String?): String? {
        if (folderId.isNullOrBlank()) return null
        return if (isProject(folderId)) folderId else PROJECT_PREFIX + folderId
    }

    /**
     * The space key a chat session should use: the project's key when it is in
     * a project, its own id otherwise.
     */
    fun spaceKey(sessionId: String, folderId: String?): String =
        projectKey(folderId) ?: sessionId

    /** Inverse of [projectKey]; `null` for a plain chat-session key. */
    fun folderIdOf(key: String): String? =
        if (isProject(key)) key.removePrefix(PROJECT_PREFIX) else null

    /**
     * Directory name segment for [key] under its root — what actually appears
     * on disk. Kept separate from the key so the `project:` prefix never
     * becomes a directory name (`:` is legal on ext4 but hostile in shell
     * quoting and in `minis://` URLs).
     */
    fun dirName(key: String): String {
        val raw = if (isProject(key)) key.removePrefix(PROJECT_PREFIX) else key
        // Defence in depth: a malicious/typo'd id must not escape the root.
        require(raw.isNotEmpty()) { "empty space key" }
        require('/' !in raw && '\\' !in raw && raw != "." && raw != "..") {
            "unsafe space key: $key"
        }
        return raw
    }

    /** Host root of [key]'s space, without any subdirectory. */
    fun baseDir(filesDir: File, key: String): File {
        val root = if (isProject(key)) SPACES_ROOT else SESSIONS_ROOT
        return File(filesDir, "$root/${dirName(key)}")
    }

    /** Host directory for `workspace` / `attachments` / `offloads` / `browser`. */
    fun subDir(filesDir: File, key: String, subdir: String): File =
        File(baseDir(filesDir, key), subdir)

    /**
     * Every space key that could own [filesDir]-relative data — used by the
     * storage screen and the orphan reporters, which must not silently skip a
     * root that is new in this build.
     */
    fun roots(): List<String> = listOf(SESSIONS_ROOT, SPACES_ROOT)

    /** Human-readable label for logs and tool output ("proje" vs "sohbet"). */
    fun label(key: String): String =
        if (isProject(key)) "project:${dirName(key).take(8)}" else "session:${dirName(key).take(8)}"
}
