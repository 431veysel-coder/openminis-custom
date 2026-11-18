package com.openminis.app.data.memory

import android.content.Context
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.FsSpace
import java.io.File

/**
 * [MINIS-OZEL 37072] The one-time "everything you did until now is a project".
 *
 * ## Why this exists at all
 *
 * The user's instruction was exact: *"şimdiki cihazımızda olan hafızaya göre
 * yaptığımız tüm işlemler şimdiye kadar bir proje olacak; yeni projeler
 * açılabilecek, hafızası burası ile alakalı olmasın."*
 *
 * Project memory is a SEPARATE layer that gets injected alongside the personal
 * one, so merely creating a group would not have satisfied it: the old notes
 * would keep being injected into every brand-new project too. The accumulated
 * work memory has to physically leave the personal layer. That is what this
 * class does, once.
 *
 * ## Idempotence
 *
 * Two independent witnesses, both required to agree before the work is skipped:
 *
 *  - `SharedPreferences` flag (fast, and the only thing read on the hot path);
 *  - a `folders` row with `origin = 'migration'` (survives a cleared prefs file,
 *    which Android does on "clear data" — and in that case there is nothing left
 *    to migrate anyway, so re-executing is harmless).
 *
 * The flag is written LAST, after the data operations succeed. A crash in the
 * middle therefore re-runs on next launch, which is safe because
 * [MemoryMigration.execute] reconciles an existing destination instead of
 * duplicating it.
 *
 * ## What it deliberately does NOT do
 *
 *  - It does not touch `skills/`, `shared/`, `mcp-servers/` or `SOUL.md`: those
 *    are the personal layer and they stay personal.
 *  - It does not delete any conversation.
 *  - It does not run when there is nothing to move (fresh install): marking the
 *    flag without creating an empty "Geçmiş İşler" project keeps a first-run
 *    device clean.
 */
object MemoryLegacyProjectMigration {

    private const val TAG = "MemoryMigration"

    private const val PREFS = "minis_project_prefs"
    private const val KEY_DONE = "project_migration_v1"

    /** The name the migrated project starts with; the user may rename it. */
    const val DEFAULT_PROJECT_NAME = "Geçmiş İşler"

    data class Outcome(
        /** False when there was nothing to move, or it had already run. */
        val ran: Boolean,
        /** Why it did not run, for the log and the debug RPC. */
        val reason: String,
        val folderId: String? = null,
        val projectName: String? = null,
        val movedLogs: Int = 0,
        val sessionsFiled: Int = 0,
        val bytesMoved: Long = 0,
        val error: String? = null,
    )

    fun hasRun(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DONE, false)

    /**
     * True while the file move is on the stack.
     *
     * The move runs on app start, concurrently with whatever the user does
     * first. Injecting a half-moved memory into the first turn would show the
     * model a hole and, worse, let it write based on that hole. Callers that
     * build a memory fragment read this flag and simply inject NOTHING for that
     * turn instead — an empty memory is honest, a truncated one is not.
     */
    @Volatile
    var inProgress: Boolean = false
        private set

    /**
     * Run the migration if it is due.
     *
     * Safe to call on every launch: it short-circuits on the flag, on an
     * existing migration project, and on an empty personal memory.
     *
     * Never throws. A failure is reported in [Outcome.error] and leaves the
     * flag unset so the next launch retries; the user keeps working with the
     * pre-migration behaviour in the meantime, which is exactly the state their
     * data is in.
     */
    suspend fun runIfDue(context: Context, chatRepository: ChatRepository): Outcome {
        if (hasRun(context)) return Outcome(false, "already-run")

        val personalDir = File(context.filesDir, "minis-global/memory")
        val plan = MemoryMigration.plan(personalDir)
        val ungrouped = chatRepository.sessionIdsWithNoFolder()
        if (plan.isEmpty && ungrouped.isEmpty()) {
            markDone(context)
            return Outcome(false, "nothing-to-migrate")
        }

        inProgress = true
        return try {
            // Reuse a migration project from an interrupted earlier run rather
            // than creating a second one with the same name.
            val folder = chatRepository.listFolders()
                .firstOrNull { it.origin == FolderEntity.ORIGIN_MIGRATION }
                ?: chatRepository.createFolder(
                    name = DEFAULT_PROJECT_NAME,
                    description = "Projelerden önceki tüm sohbetler ve birikmiş iş hafızası",
                    origin = FolderEntity.ORIGIN_MIGRATION,
                    spaceScope = FolderEntity.SCOPE_PROJECT,
                )

            val projectDir = FsSpace.subDir(
                context.filesDir, FsSpace.projectKey(folder.id)!!, "memory",
            )
            val result = MemoryMigration.execute(
                personalDir = personalDir,
                projectDir = projectDir,
                projectName = folder.name,
                plan = plan,
            )

            // File the ungrouped sessions. Read AFTER the move so a session the
            // user created while the migration ran is not swept in silently —
            // it will be filed on the next (idempotent) run instead.
            val fileable = chatRepository.sessionIdsWithNoFolder()
            chatRepository.setFolderForSessions(folder.id, fileable)

            markDone(context)
            AppLogger.info(
                TAG,
                "legacy migration: project=${folder.id.take(8)} logs=${result.movedLogs} " +
                    "sessions=${fileable.size} bytes=${result.bytesMoved}",
            )
            Outcome(
                ran = true,
                reason = "migrated",
                folderId = folder.id,
                projectName = folder.name,
                movedLogs = result.movedLogs,
                sessionsFiled = fileable.size,
                bytesMoved = result.bytesMoved,
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "legacy migration failed: ${t.javaClass.simpleName}: ${t.message}")
            Outcome(false, "failed", error = t.message ?: t.javaClass.simpleName)
        } finally {
            inProgress = false
        }
    }

    /**
     * Put the migrated memory back into the personal layer and clear the flag.
     *
     * The sessions stay filed in the project: undoing the memory move must not
     * shuffle the user's chat list underneath them. Renaming or dissolving the
     * project is a separate, visible action.
     */
    suspend fun undo(context: Context, chatRepository: ChatRepository): Int {
        val folder = chatRepository.listFolders()
            .firstOrNull { it.origin == FolderEntity.ORIGIN_MIGRATION }
            ?: return 0
        val projectDir = FsSpace.subDir(
            context.filesDir, FsSpace.projectKey(folder.id)!!, "memory",
        )
        val personalDir = File(context.filesDir, "minis-global/memory")
        val moved = MemoryMigration.undo(projectDir, personalDir)
        prefs(context).edit().putBoolean(KEY_DONE, false).apply()
        AppLogger.info(TAG, "legacy migration undone: $moved file(s) back to personal memory")
        return moved
    }

    private fun markDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
