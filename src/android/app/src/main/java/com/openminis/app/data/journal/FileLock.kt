package com.openminis.app.data.journal

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * [MINIS-OZEL 11.3.1] Serialises writes to the same file, and detects a write
 * onto content that changed since it was read.
 *
 * ## The two failures this prevents
 *
 * 1. **Two writers, one file, last one wins silently.** Parallel sub agents and
 *    Station panels all reach the same filesystem. Two `file_write` calls to the
 *    same path interleaved at the wrong moment produce a file that is neither
 *    result, and nothing reports it — the transcript shows two successes. A
 *    per-path lock turns that into "one of them waited", which is merely slow.
 *
 * 2. **Editing a stale read.** The agent reads a file, then something else
 *    (another agent, the user, a build step) rewrites it, then the agent writes
 *    its version — discarding the change it never saw. The fingerprint check
 *    compares what was read with what is on disk now, and refuses with an
 *    explanation instead of overwriting.
 *
 * ## Why per-path locks and not one global lock
 *
 * A single lock would serialise every write in the app, including writes to
 * unrelated files, and would make a Station run of four panels behave like one.
 * The contention is per path because the conflict is per path.
 *
 * ## Why the lock map is cleaned up
 *
 * A long-lived map keyed by path is a small leak per file touched. Entries are
 * dropped when the last holder releases, so an idle app holds nothing.
 */
object FileLock {

    private val locks = ConcurrentHashMap<String, Holder>()

    private class Holder {
        val lock = ReentrantLock()
        var references = 0
    }

    /**
     * Fingerprint of a file's content at a moment in time.
     *
     * Size + lastModified alone are cheap but weak: a same-size rewrite inside
     * one filesystem timestamp tick looks identical, which is exactly what a
     * fast agent run produces. The content hash is what makes the check honest,
     * and it is only computed for files small enough to have been read anyway.
     */
    data class Fingerprint(
        val size: Long,
        val modified: Long,
        val hash: String,
    ) {
        fun matches(other: Fingerprint): Boolean =
            size == other.size && modified == other.modified && hash == other.hash
    }

    /** Above this, hashing costs more than the check is worth. */
    private const val MAX_HASH_BYTES = 4L * 1024 * 1024

    fun fingerprint(file: File): Fingerprint? {
        if (!file.exists() || !file.isFile) return null
        val size = file.length()
        val modified = file.lastModified()
        val hash = if (size <= MAX_HASH_BYTES) {
            try {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                // Unreadable: fall back to what we have. The size/mtime check
                // still catches the common case.
                ""
            }
        } else {
            ""
        }
        return Fingerprint(size, modified, hash)
    }

    /**
     * Runs [block] with the lock for [path] held.
     *
     * Blocks the calling thread; callers are already off the main thread (the
     * file tools run inside FileToolIO), and the critical section is one file
     * write.
     */
    fun <T> withLock(path: String, block: () -> T): T {
        val key = normalise(path)
        val holder = locks.compute(key) { _, existing ->
            (existing ?: Holder()).also { it.references++ }
        }!!
        holder.lock.lock()
        try {
            return block()
        } finally {
            holder.lock.unlock()
            locks.compute(key) { _, existing ->
                if (existing == null) {
                    null
                } else {
                    existing.references--
                    if (existing.references <= 0) null else existing
                }
            }
        }
    }

    // ── stale-read tracking ─────────────────────────────────────────────────

    /**
     * Last fingerprint each session is known to have seen for a path.
     *
     * In memory on purpose: this is a guard against a write racing a read
     * inside one app run, not a durable record. After a restart the agent has
     * no memory of the file anyway and re-reads it before editing.
     *
     * The key includes the session so two sessions can hold different views of
     * the same file without one of them poisoning the other's guard.
     */
    private val reads = ConcurrentHashMap<String, Fingerprint>()

    private fun readKey(sessionId: String, path: String) = "$sessionId\u0000${normalise(path)}"

    /** Records what a session just read (or just wrote). */
    fun noteSeen(sessionId: String, path: String, file: File) {
        val fp = fingerprint(file) ?: return
        reads[readKey(sessionId, path)] = fp
    }

    /** What this session last saw at [path], if anything. */
    fun lastSeen(sessionId: String, path: String): Fingerprint? = reads[readKey(sessionId, path)]

    /**
     * True when writing [path] would discard a change this session never saw.
     * A path this session has not read at all is not a conflict — that is a
     * blind create-or-overwrite, which the tool documents and allows.
     */
    fun isStaleFor(sessionId: String, path: String, file: File): Boolean {
        val seen = lastSeen(sessionId, path) ?: return false
        if (!file.exists()) return true
        return !stillMatches(file, seen)
    }

    /** A path as a lock key: absolute-ish, no `.`/`..`, no trailing slash. */
    fun normalise(path: String): String {
        val parts = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /**
     * Why a write was refused, phrased so the model can recover: it should
     * re-read and re-apply, not retry the same bytes.
     */
    fun conflictMessage(path: String): String =
        "Error: $path changed since it was read (another agent, the user, or a build " +
            "step wrote it). Re-read the file and apply the change to its current " +
            "content — retrying this call would discard the other change."

    /**
     * Whether the file still matches what the caller read.
     *
     * [expected] is null when the caller did not read it (a blind overwrite); in
     * that case there is nothing to verify and the write proceeds, because
     * refusing every blind write would break create-and-write.
     */
    fun stillMatches(file: File, expected: Fingerprint?): Boolean {
        if (expected == null) return true
        val current = fingerprint(file) ?: return false // it vanished
        return current.matches(expected)
    }
}
