package com.openminis.app.ozel

import com.openminis.app.provider.AccountIdDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 11.1] Account-id providers: the URL is the whole feature.
 *
 * Cloudflare's OpenAI-compatible root is
 * `/client/v4/accounts/{id}/ai/v1`. Get that string wrong and the user sees a
 * 404 from a provider the app just told them to configure — which reads as "the
 * app is broken", not "the id is missing". So the string construction and the
 * local validation are pinned here rather than left to a UI call site.
 */
class AccountIdDiscoveryTest {

    @Test
    fun `cloudflare base url carries the account id and ends in v1`() {
        val id = "0123456789abcdef0123456789abcdef"
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/$id/ai/v1",
            AccountIdDiscovery.baseUrlFor(AccountIdDiscovery.Kind.CLOUDFLARE, id),
        )
    }

    @Test
    fun `a blank account id yields no url rather than a broken one`() {
        // Half a URL would produce a 404 that looks like a provider outage.
        assertNull(AccountIdDiscovery.baseUrlFor(AccountIdDiscovery.Kind.CLOUDFLARE, "   "))
        assertNull(AccountIdDiscovery.baseUrlFor(AccountIdDiscovery.Kind.CLOUDFLARE, ""))
    }

    @Test
    fun `a provider that needs no account id has no synthetic url`() {
        assertNull(AccountIdDiscovery.baseUrlFor(AccountIdDiscovery.Kind.NONE, "anything"))
    }

    @Test
    fun `cloudflare account ids are 32 hex characters`() {
        assertTrue(
            AccountIdDiscovery.looksLikeCloudflareAccountId("0123456789abcdef0123456789ABCDEF"),
        )
        // Padded input is accepted: the field is pasted from a dashboard.
        assertTrue(
            AccountIdDiscovery.looksLikeCloudflareAccountId(" 0123456789abcdef0123456789abcdef "),
        )
    }

    @Test
    fun `anything else is rejected before it reaches the network`() {
        assertFalse(AccountIdDiscovery.looksLikeCloudflareAccountId(""))
        assertFalse(AccountIdDiscovery.looksLikeCloudflareAccountId("0123"))
        assertFalse(
            "one char short",
            AccountIdDiscovery.looksLikeCloudflareAccountId("0123456789abcdef0123456789abcde"),
        )
        assertFalse(
            "not hex",
            AccountIdDiscovery.looksLikeCloudflareAccountId("0123456789abcdef0123456789abcdeZ"),
        )
    }

    @Test
    fun `a single discovered account is the one to fill in`() {
        val one = AccountIdDiscovery.Discovery(
            accounts = listOf(AccountIdDiscovery.AccountRef("abc", "Personal")),
        )
        assertEquals("abc", one.only?.id)

        // Several accounts must NOT auto-pick: silently choosing the first one
        // is how a user ends up on someone else's account in a shared login.
        val many = AccountIdDiscovery.Discovery(
            accounts = listOf(
                AccountIdDiscovery.AccountRef("a", "Work"),
                AccountIdDiscovery.AccountRef("b", "Personal"),
            ),
        )
        assertNull(many.only)
    }
}
