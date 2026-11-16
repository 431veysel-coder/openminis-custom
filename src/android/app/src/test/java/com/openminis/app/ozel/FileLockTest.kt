package com.openminis.app.ozel

import com.openminis.app.data.journal.FileLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [MINIS-OZEL 11.3.1] The two concurrency guards under the file tools.
 *
 * These tests exist because the failure they prevent is silent: two writes that
 * interleave produce a plausible-looking file and two successful tool results,
 * so nothing anywhere reports that one agent's work was thrown away.
 */
class FileLockTest {

    private val tmp: File = Files.createTempDirectory("filelock-test").toFile()

    private fun file(name: String, content: String): File {
        val f = File(tmp, name)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    // ── locking ─────────────────────────────────────────────────────────────

    @Test
    fun `writes to the same path are serialised`() {
        val counter = AtomicInteger(0)
        val inside = AtomicInteger(0)
        val overlap = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)

        repeat(8) {
            pool.submit {
                start.await()
                FileLock.withLock("/var/minis/workspace/same.txt") {
                    if (inside.incrementAndGet() > 1) overlap.incrementAndGet()
                    counter.incrementAndGet()
                    Thread.sleep(5)
                    inside.decrementAndGet()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(8, counter.get())
        assertEquals("two holders inside the same path's lock", 0, overlap.get())
    }

    @Test
    fun `different paths do not block each other`() {
        val aHolding = CountDownLatch(1)
        val bDidRun = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        pool.submit {
            FileLock.withLock("/a.txt") {
                aHolding.countDown()
                // b must be able to enter while we hold a
                assertTrue("b was blocked by an unrelated path's lock", bDidRun.await(5, TimeUnit.SECONDS))
            }
        }
        aHolding.await(5, TimeUnit.SECONDS)
        pool.submit {
            FileLock.withLock("/b.txt") { bDidRun.countDown() }
        }
        assertTrue(bDidRun.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun `equivalent spellings share one lock`() {
        assertEquals(FileLock.normalise("/a/b/../c"), FileLock.normalise("/a/./c"))
        assertEquals("/a/c", FileLock.normalise("/a/./b/../c"))
    }

    // ── stale reads ─────────────────────────────────────────────────────────

    @Test
    fun `a write after a matching read is not a conflict`() {
        val f = file("plain.txt", "one")
        FileLock.noteSeen("s1", "/w/plain.txt", f)

        assertFalse(FileLock.isStaleFor("s1", "/w/plain.txt", f))
    }

    @Test
    fun `a change after the read is a conflict`() {
        val f = file("changed.txt", "one")
        FileLock.noteSeen("s1", "/w/changed.txt", f)
        Thread.sleep(5)
        f.writeText("something else entirely")

        assertTrue(FileLock.isStaleFor("s1", "/w/changed.txt", f))
    }

    @Test
    fun `a same-size rewrite inside one timestamp tick is still caught by the hash`() {
        // This is the case a size+mtime check misses, and it is exactly what a
        // fast agent run produces: same length, same second.
        val f = file("tick.txt", "aaaa")
        val before = FileLock.fingerprint(f)!!
        f.setLastModified(before.modified)
        f.writeText("bbbb")
        f.setLastModified(before.modified)

        val after = FileLock.fingerprint(f)!!
        assertEquals(before.size, after.size)
        assertEquals(before.modified, after.modified)
        assertNotEquals("the hash is what makes this check honest", before.hash, after.hash)
        assertFalse(before.matches(after))
    }

    @Test
    fun `another session's read does not make this one stale`() {
        val f = file("shared.txt", "one")
        FileLock.noteSeen("s1", "/w/shared.txt", f)
        Thread.sleep(5)
        f.writeText("edited by the other session")

        // s2 never read it, so it has nothing to be stale against.
        assertFalse(FileLock.isStaleFor("s2", "/w/shared.txt", f))
        assertTrue(FileLock.isStaleFor("s1", "/w/shared.txt", f))
    }

    @Test
    fun `a path never seen is not a conflict`() {
        val f = file("blind.txt", "x")
        assertNull(FileLock.lastSeen("s9", "/w/blind.txt"))
        assertFalse(FileLock.isStaleFor("s9", "/w/blind.txt", f))
    }

    @Test
    fun `a conflict message tells the model to re-read, not to retry`() {
        val msg = FileLock.conflictMessage("/var/minis/workspace/a.txt")
        assertTrue(msg.contains("Re-read"))
        assertTrue(msg.contains("/var/minis/workspace/a.txt"))
    }
}
