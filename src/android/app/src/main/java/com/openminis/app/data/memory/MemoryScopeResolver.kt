package com.openminis.app.data.memory

import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.sandbox.FsSpace
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [MINIS-OZEL 37073] Which memory a chat is allowed to read and write.
 *
 * ## The two layers
 *
 * ```
 * personal : <filesDir>/minis-global/memory/         GLOBAL.md + daily logs
 * project  : <filesDir>/minis-spaces/<fid>/memory/   PROJECT.md + daily logs
 * ```
 *
 * Both are ordinary [MemoryRepository] instances — the class was already
 * parameterised by `memoryDir`, so no rewrite was needed and every existing
 * behaviour (timestamped prepend, revoke/replace by content, keyword search,
 * the 30 KB output cap) is inherited unchanged. Only the DIRECTORY differs,
 * and that is the entire isolation mechanism.
 *
 * ## Why instances are cached
 *
 * `memory_write` and the prompt builder run per turn; constructing a repository
 * per call would re-`mkdirs()` a directory on the main-ish path every time.
 * `ConcurrentHashMap` rather than a plain map because a shell command and a
 * prompt build can touch different projects at the same moment.
 *
 * ## The rule the model sees
 *
 * Reads: personal always, project additionally (the user's decision in §13 of
 * the design doc — "görsün, yazmasın"). Writes: the PROJECT by default when in
 * a project, personal only on an explicit `scope=global`. Outside a project
 * everything collapses to personal, i.e. today's behaviour exactly.
 */
class MemoryScopeResolver(
    private val filesDir: File,
    private val personal: MemoryRepository,
) {

    /** What a `memory_write` / `memory_get` call means for this chat. */
    data class Binding(
        /** Null when the chat is not in a project (or the project has no memory). */
        val project: MemoryRepository?,
        val projectName: String?,
        /** Folder id, for `NoteStore` scope and debug output. */
        val folderId: String?,
    ) {
        val inProject: Boolean get() = project != null

        /** The repository a write goes to when the caller says nothing. */
        fun defaultWriteRepo(): MemoryRepository? = project
    }

    private val cache = ConcurrentHashMap<String, MemoryRepository>()

    /** Personal layer — never null, it is the pre-project behaviour. */
    fun personal(): MemoryRepository = personal

    /**
     * The project's memory repository, created on first use.
     *
     * [enabled] false (or a null id) returns null: a project whose memory the
     * user switched off must behave as if it had none — no injection, and
     * `memory_write` falls back to personal rather than silently filling a
     * directory nobody reads.
     */
    fun project(folderId: String?, projectName: String?, enabled: Boolean): MemoryRepository? {
        if (!enabled) return null
        val key = FsSpace.projectKey(folderId) ?: return null
        // projectName is accepted for signature symmetry with [bind]; the
        // directory depends only on the id, so a rename never moves files.
        return cache.getOrPut(key) { MemoryRepository(memoryDirFor(key)) }
    }

    /** Directory a project's memory lives in — sibling of its workspace. */
    fun memoryDirFor(spaceKey: String): File =
        FsSpace.subDir(filesDir, spaceKey, "memory")

    /**
     * Bind a binding from the facts a chat already knows. Kept here (rather than
     * in the view model) so the fallback rules are one testable function.
     */
    fun bind(folderId: String?, projectName: String?, projectMemoryEnabled: Boolean): Binding =
        Binding(
            project = project(folderId, projectName, projectMemoryEnabled),
            projectName = projectName,
            folderId = folderId,
        )

    /** Drop a cached instance (project deleted, or re-pointed). Idempotent. */
    fun forget(folderId: String) {
        FsSpace.projectKey(folderId)?.let { cache.remove(it) }
    }

    /** Test/debug: how many project repositories are live. */
    internal fun cachedCount(): Int = cache.size
}
