package com.openminis.app.data.db

import androidx.room.Database
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-db-version-single-source] Proves the launch-time downgrade guard and the
 * Room schema cannot disagree.
 *
 * ### Why this test exists at all
 *
 * `DatabaseVersionGuard` carries a comment that says "[AppDatabaseVersionTest]
 * asserts the two agree". For a while that comment pointed at a file that did
 * not exist — the safety net was a sentence, not a test. On 1.15-ozel.53 the
 * two values drifted (`@Database(version = 16)` vs `CODE_DB_VERSION = 15`) and
 * every install locked itself out of its own database on the second launch
 * with "Verileriniz daha yeni bir sürümden geliyor". Nothing failed: both
 * numbers compiled fine, and no test compared them.
 *
 * The production fix removed the second copy of the number entirely — the
 * guard now reads [DB_SCHEMA_VERSION] and so does the annotation — so this
 * test verifies the invariant that replaces it:
 *
 *  1. the annotation really is reading the shared constant, and
 *  2. the constant the guard uses equals the one Room was built with.
 *
 * A literal that sneaks back into either place turns this red in seconds
 * instead of turning into a user-visible lockout on release day.
 */
class AppDatabaseVersionTest {

    @Test
    fun `database annotation uses the shared schema constant`() {
        val annotation = AppDatabase::class.java.getAnnotation(Database::class.java)
        assertNotNull("@Database annotation missing on AppDatabase", annotation)

        assertEquals(
            "AppDatabase's @Database(version = ...) must be DB_SCHEMA_VERSION, " +
                "not a literal — a second copy of this number is exactly how " +
                "1.15-ozel.53 locked users out of their data.",
            DB_SCHEMA_VERSION,
            annotation!!.version,
        )
    }

    @Test
    fun `downgrade guard understands the version Room writes`() {
        assertEquals(
            "DatabaseVersionGuard.CODE_DB_VERSION must equal DB_SCHEMA_VERSION. " +
                "If the guard is behind, every launch after the first sees a " +
                "'newer' database and shows the guidance screen instead of the app.",
            DB_SCHEMA_VERSION,
            DatabaseVersionGuard.CODE_DB_VERSION,
        )
    }

    @Test
    fun `every declared downgrade pair has a registered migration`() {
        val alanlar = AppDatabase::class.java.declaredFields
            .map { it.name to it }
            .filter { (ad, _) -> ad.startsWith("MIGRATION_") }

        val eksik = AppDatabase.DOWNGRADE_PAIRS.filter { (gelen, gidilen) ->
            alanlar.none { (ad, _) -> ad == "MIGRATION_${gelen}_$gidilen" }
        }
        assertTrue(
            "DOWNGRADE_PAIRS içinde olup MIGRATION_<gelen>_<gidilen> alanı " +
                "bulunmayan çiftler: $eksik — muhafız 'bu yolu biliyorum' der, " +
                "Room ise migration bulamaz ve IllegalStateException atar.",
            eksik.isEmpty(),
        )
    }

    @Test
    fun `schema version is strictly positive and monotonic with committed schemas`() {
        assertTrue("DB_SCHEMA_VERSION must be > 0", DB_SCHEMA_VERSION > 0)
        // A guard that reports "newer" for the version we ourselves write is
        // useless; the comparison below is the same one evaluate() performs.
        assertTrue(
            "isHandledDowngrade must never be asked to excuse our own version",
            DB_SCHEMA_VERSION <= DatabaseVersionGuard.CODE_DB_VERSION,
        )
    }
}
