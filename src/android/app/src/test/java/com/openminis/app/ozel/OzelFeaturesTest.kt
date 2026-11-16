package com.openminis.app.ozel

import com.openminis.app.data.OzelFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL] The feature-switch contract.
 *
 * These are blocking tests (`com.openminis.app.ozel.*`). The rule they protect
 * is the one that makes a switch safe to hand to a user: **off hides an entry
 * point, it never deletes data.** That is a promise about defaults and keys, so
 * it can be tested without an Android context — a Robolectric-free test here
 * means it runs in the fast job rather than the emulator one.
 *
 * What is NOT here: the SharedPreferences round-trip (that needs a Context and
 * belongs in an instrumented test). What is here is the part that actually
 * breaks in review — a key renamed without a default, or a default flipped by
 * accident, which would silently change behaviour on every existing install.
 */
class OzelFeaturesTest {

    @Test
    fun `every feature has a unique key`() {
        val keys = OzelFeatures.Feature.entries.map { it.key }
        assertEquals("duplicate feature key: $keys", keys.size, keys.toSet().size)
    }

    @Test
    fun `keys are stable snake_case identifiers`() {
        // A key is a persisted string. Changing its spelling silently resets
        // the user's choice to the default, so the shape is pinned here: the
        // test forces a deliberate decision instead of a rename in passing.
        val ok = Regex("^[a-z][a-z0-9_]*$")
        OzelFeatures.Feature.entries.forEach { f ->
            assertTrue("${f.name} key '${f.key}' is not snake_case", ok.matches(f.key))
        }
    }

    @Test
    fun `the developer server is off by default`() {
        // The one switch whose "on" state opens a socket that can read provider
        // keys, take screenshots and drive the UI. Defaulting it on would undo
        // the point of having a switch at all.
        assertFalse(OzelFeatures.Feature.DEV_SERVER.defaultEnabled)
    }

    @Test
    fun `every user-facing feature is on by default`() {
        // An update must not silently remove something the user was using. The
        // developer server above is the only deliberate exception.
        val userFacing = OzelFeatures.Feature.entries - OzelFeatures.Feature.DEV_SERVER
        userFacing.forEach { f ->
            assertTrue("${f.name} defaults to off — an update would hide it", f.defaultEnabled)
        }
    }

    @Test
    fun `parse round-trips every key and rejects anything else`() {
        OzelFeatures.Feature.entries.forEach { f ->
            assertEquals(f, OzelFeatures.Feature.parse(f.key))
            assertEquals(f, OzelFeatures.Feature.parse("  ${f.key}  "))
        }
        assertEquals(null, OzelFeatures.Feature.parse(null))
        assertEquals(null, OzelFeatures.Feature.parse(""))
        assertEquals(null, OzelFeatures.Feature.parse("not_a_feature"))
        // Case matters: keys are persisted verbatim, so a fuzzy match would
        // hide a typo that otherwise just resets to the default.
        assertEquals(null, OzelFeatures.Feature.parse("LIVE_TASKS"))
    }
}
