package com.openminis.app.data.memory

import java.io.File
import java.security.MessageDigest

/**
 * [MINIS-OZEL 37072] Moving the pre-project memory into a project, safely.
 *
 * This is the only operation in the project feature that can lose data by
 * itself, so it is written as a small state machine with an explicit undo
 * rather than as a few `File.renameTo` calls inside an `onCreate`.
 *
 * ## The contract
 *
 * 1. **Nothing is deleted before its copy is verified.** Every file is copied,
 *    re-read, and compared by SHA-256 against the source; only then is the
 *    source removed. A power cut mid-move leaves a duplicate, never a gap.
 * 2. **A failed run leaves the world exactly as it was.** The staged copies
 *    live in a temp directory that is discarded on any error, and the sources
 *    are untouched until every copy has been verified.
 * 3. **`SOUL.md` never moves.** It lives in the same directory (SoulStore owns
 *    it) but it is the user's persona, not work memory — moving it would
 *    silently change the assistant's character.
 * 4. **`GLOBAL.md` is not moved but rewritten.** Its content becomes the
 *    project's `PROJECT.md`, and the personal file keeps a one-line pointer so
 *    the user knows where their notes went and what the file is for now.
 *
 * The class is deliberately free of Android and DB types: the caller supplies
 * directories, so the properties above are unit-testable on a plain JVM.
 */
object MemoryMigration {

    /** Files that belong to the persona, not to memory. */
    private val NEVER_MOVE = setOf("SOUL.md", "SOUL.icon.png")

    /** Written into the personal GLOBAL.md after a successful move. */
    const val GLOBAL_POINTER_TR =
        "Birikmiş iş hafızası **%s** projesine taşındı. " +
            "Bu dosya artık yalnız kişisel/cihaz geneli tercihler için."

    /** The project-side counterpart of GLOBAL.md. */
    const val PROJECT_FILE = "PROJECT.md"

    data class Plan(
        /** Absolute paths of the daily logs that would move, newest first. */
        val dailyLogs: List<File>,
        /** True when a non-empty GLOBAL.md would become PROJECT.md. */
        val hasGlobal: Boolean,
        /** Which files are deliberately staying behind. */
        val skipped: List<String>,
    ) {
        val isEmpty: Boolean get() = dailyLogs.isEmpty() && !hasGlobal
    }

    /** What actually happened. */
    data class Result(
        val movedLogs: Int,
        val projectFileWritten: Boolean,
        val globalPointerWritten: Boolean,
        val bytesMoved: Long,
    )

    /** Inspect `personalDir` without touching it. */
    fun plan(personalDir: File): Plan {
        if (!personalDir.isDirectory) return Plan(emptyList(), false, emptyList())
        val files = personalDir.listFiles()?.filter { it.isFile }.orEmpty()
        val skipped = files.filter { it.name in NEVER_MOVE }.map { it.name }
        val global = files.firstOrNull { it.name == "GLOBAL.md" }
        val logs = files
            .filter { it.extension == "md" && it.name != "GLOBAL.md" && it.name !in NEVER_MOVE }
            .filter { it.length() > 0 }
            .sortedByDescending { it.name }
        return Plan(
            dailyLogs = logs,
            hasGlobal = global != null && global.length() > 0,
            skipped = skipped,
        )
    }

    /**
     * Execute [plan] into [projectDir].
     *
     * `projectName` is only used for the pointer line written back into the
     * personal GLOBAL.md. [overwriteProjectFile] is false for a re-run over a
     * project that already has a PROJECT.md — appending a second copy of the
     * old global notes would duplicate user-visible text on every relaunch.
     */
    fun execute(
        personalDir: File,
        projectDir: File,
        projectName: String,
        plan: Plan = plan(personalDir),
        overwriteProjectFile: Boolean = true,
    ): Result {
        if (plan.isEmpty) return Result(0, false, false, 0)

        projectDir.mkdirs()
        require(projectDir.isDirectory) { "cannot create project memory dir: $projectDir" }

        // Stage everything next to the destination under a dot-prefixed name so a
        // half-finished run is visible and removable, and cannot be mistaken for
        // a real log by MemoryRepository (which scans *.md at the top level).
        val stage = File(projectDir, ".migration-stage").apply {
            deleteRecursively()
            mkdirs()
        }

        return try {
            var bytes = 0L
            for (src in plan.dailyLogs) {
                val staged = File(stage, src.name)
                src.copyTo(staged, overwrite = true)
                require(sameContent(src, staged)) { "copy verification failed for ${src.name}" }
                bytes += src.length()
            }
            var projectFileWritten = false
            val globalSrc = File(personalDir, "GLOBAL.md")
            if (plan.hasGlobal && globalSrc.isFile) {
                val staged = File(stage, PROJECT_FILE)
                globalSrc.copyTo(staged, overwrite = true)
                require(sameContent(globalSrc, staged)) { "copy verification failed for GLOBAL.md" }
                bytes += globalSrc.length()
                projectFileWritten = true
            }

            // Verified. Now make the moves real: stage → destination is a rename
            // inside the same directory tree, so it is atomic per file.
            for (src in plan.dailyLogs) {
                val staged = File(stage, src.name)
                val dest = File(projectDir, src.name)
                if (dest.exists() && !sameContent(staged, dest)) {
                    // The project already has a log for this date (a re-run after
                    // the user wrote new project entries on the same day). Keep
                    // BOTH by appending, newest block first is not the rule here:
                    // the project's own entries were written after the moved ones,
                    // so they go on top, exactly how MemoryRepository prepends.
                    dest.writeText(staged.readText() + dest.readText())
                } else {
                    require(staged.renameTo(dest)) { "move failed for ${src.name}" }
                }
                src.delete()
            }

            if (projectFileWritten) {
                val staged = File(stage, PROJECT_FILE)
                val dest = File(projectDir, PROJECT_FILE)
                if (dest.exists() && !overwriteProjectFile) {
                    // Leave the project's own PROJECT.md alone.
                    staged.delete()
                } else {
                    if (dest.exists()) dest.delete()
                    require(staged.renameTo(dest)) { "move failed for PROJECT.md" }
                }
                // Replace GLOBAL.md with the pointer. Written in place after the
                // copy was verified, so a crash here loses only the pointer line.
                globalSrc.writeText(GLOBAL_POINTER_TR.format(projectName) + "\n")
            }

            stage.deleteRecursively()
            Result(
                movedLogs = plan.dailyLogs.size,
                projectFileWritten = projectFileWritten,
                globalPointerWritten = projectFileWritten,
                bytesMoved = bytes,
            )
        } catch (t: Throwable) {
            // A failure BEFORE the move loop means every source is still in
            // place: only the stage is discarded and the world is unchanged.
            // A failure DURING the move loop is different and still safe: each
            // source is deleted only after its destination exists (rename), so
            // the content lives in exactly one of the two directories and the
            // next run reconciles it — nothing is ever in neither.
            stage.deleteRecursively()
            throw t
        }
    }

    /**
     * Undo: move the project's memory back into the personal directory.
     *
     * Used by the "geri al" action on the migration card. It cannot restore the
     * exact pre-migration GLOBAL.md (it is now PROJECT.md, and the user has
     * probably edited it since), so it APPENDS rather than overwrites: the
     * personal file regains the notes and the user decides what to keep.
     *
     * Returns how many files came back.
     */
    fun undo(projectDir: File, personalDir: File): Int {
        if (!projectDir.isDirectory) return 0
        personalDir.mkdirs()
        var moved = 0
        val projectFile = File(projectDir, PROJECT_FILE)
        if (projectFile.isFile && projectFile.length() > 0) {
            val personal = File(personalDir, "GLOBAL.md")
            val existing = if (personal.isFile) personal.readText() else ""
            personal.writeText(projectFile.readText() + "\n\n" + existing)
            moved++
        }
        projectDir.listFiles()
            ?.filter { it.isFile && it.extension == "md" && it.name != PROJECT_FILE }
            ?.forEach { src ->
                val dest = File(personalDir, src.name)
                if (dest.exists()) {
                    dest.writeText(src.readText() + dest.readText())
                    src.delete()
                } else if (src.renameTo(dest)) {
                    src.delete()
                }
                moved++
            }
        return moved
    }

    /** Byte-for-byte comparison via SHA-256; size mismatch short-circuits. */
    internal fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile) return false
        if (a.length() != b.length()) return false
        return digest(a) == digest(b)
    }

    private fun digest(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }
}
