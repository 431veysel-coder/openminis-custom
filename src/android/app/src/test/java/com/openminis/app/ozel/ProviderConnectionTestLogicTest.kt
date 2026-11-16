package com.openminis.app.ozel

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.ProviderConnectionTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL 11.1] The connection test's decisions, without a network.
 *
 * The value of the test to the user is entirely in the diagnosis: "wrong key",
 * "wrong address" and "host unreachable" have different fixes, and the whole
 * point of the feature is that it tells them apart. Those three are pure
 * functions of the status code and the URL, so they can be pinned here — a
 * regression that turns a 404 into "check your key" would otherwise only show
 * up on a user's phone, as advice that sends them looking in the wrong place.
 */
class ProviderConnectionTestLogicTest {

    private fun instance(
        type: ProviderType = ProviderType.openAI,
        customBaseURL: String? = null,
        appendV1: Boolean = true,
        userAgent: String? = null,
        id: String = "test",
    ) = ProviderInstance(
        id = id,
        label = "Test",
        providerType = type,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = customBaseURL,
        appendV1Suffix = appendV1,
        customUserAgent = userAgent,
    )

    // ── URL construction ────────────────────────────────────────────────────

    @Test
    fun `custom base gets the v1 suffix exactly once`() {
        assertEquals(
            "https://gateway.example.com/v1/models",
            ProviderConnectionTest.modelsUrl(instance(customBaseURL = "https://gateway.example.com"), "k"),
        )
        // A base that already carries /v1 must not become /v1/v1 — the failure
        // that produces a 404 a user reads as "the provider is down".
        assertEquals(
            "https://gateway.example.com/v1/models",
            ProviderConnectionTest.modelsUrl(instance(customBaseURL = "https://gateway.example.com/v1"), "k"),
        )
        // Trailing slash in the field, appended by hand: still one /v1.
        assertEquals(
            "https://gateway.example.com/v1/models",
            ProviderConnectionTest.modelsUrl(instance(customBaseURL = "https://gateway.example.com/"), "k"),
        )
    }

    @Test
    fun `appendV1Suffix false keeps the base as typed`() {
        assertEquals(
            "https://gateway.example.com/api/models",
            ProviderConnectionTest.modelsUrl(
                instance(customBaseURL = "https://gateway.example.com/api", appendV1 = false),
                "k",
            ),
        )
    }

    @Test
    fun `no custom base falls back to the vendor host`() {
        assertEquals(
            "https://api.anthropic.com/v1/models",
            ProviderConnectionTest.modelsUrl(instance(ProviderType.anthropic), "k"),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            ProviderConnectionTest.modelsUrl(instance(ProviderType.openRouter), "k"),
        )
    }

    @Test
    fun `gemini carries the key in the query, not a header`() {
        val url = ProviderConnectionTest.modelsUrl(instance(ProviderType.gemini), "a b+c")
        assertTrue("key must be encoded: $url", url.contains("?key=a+b%2Bc"))
        assertNull(ProviderConnectionTest.authHeaders(instance(ProviderType.gemini), "a b+c")["Authorization"])
    }

    // ── headers ─────────────────────────────────────────────────────────────

    @Test
    fun `anthropic uses x-api-key plus the version header`() {
        val h = ProviderConnectionTest.authHeaders(instance(ProviderType.anthropic), "sk-ant")
        assertEquals("sk-ant", h["x-api-key"])
        assertEquals("2023-06-01", h["anthropic-version"])
        assertNull("Anthropic rejects a bearer token here", h["Authorization"])
    }

    @Test
    fun `openai-compatible uses a bearer token`() {
        val h = ProviderConnectionTest.authHeaders(instance(ProviderType.openAI), "sk-1")
        assertEquals("Bearer sk-1", h["Authorization"])
    }

    @Test
    fun `an empty key sends no Authorization header at all`() {
        // A local gateway needs no key; "Bearer " would be a malformed header
        // and some gateways reject the request outright.
        val h = ProviderConnectionTest.authHeaders(instance(ProviderType.openAI), "")
        assertNull(h["Authorization"])
    }

    @Test
    fun `per-provider user agent override is honoured`() {
        val h = ProviderConnectionTest.authHeaders(
            instance(ProviderType.openAI, userAgent = "claude-cli/1.0"),
            "k",
        )
        assertEquals("claude-cli/1.0", h["User-Agent"])
        // Blank override must not send an empty header.
        assertNull(ProviderConnectionTest.authHeaders(instance(ProviderType.openAI, userAgent = "   "), "k")["User-Agent"])
    }

    // ── classification ──────────────────────────────────────────────────────

    @Test
    fun `status codes map to the diagnosis the user needs`() {
        assertEquals(ProviderConnectionTest.Kind.OK, ProviderConnectionTest.classify(200))
        assertEquals(ProviderConnectionTest.Kind.OK, ProviderConnectionTest.classify(204))
        assertEquals(ProviderConnectionTest.Kind.BAD_KEY, ProviderConnectionTest.classify(401))
        assertEquals(ProviderConnectionTest.Kind.FORBIDDEN, ProviderConnectionTest.classify(403))
        assertEquals(ProviderConnectionTest.Kind.NOT_FOUND, ProviderConnectionTest.classify(404))
        assertEquals(ProviderConnectionTest.Kind.RATE_LIMITED, ProviderConnectionTest.classify(429))
        assertEquals(ProviderConnectionTest.Kind.HTTP_ERROR, ProviderConnectionTest.classify(500))
        // Redirects are not success here: the probe does not follow them, and
        // an unfollowed 302 means the base URL is wrong.
        assertEquals(ProviderConnectionTest.Kind.HTTP_ERROR, ProviderConnectionTest.classify(302))
    }

    // ── body parsing ────────────────────────────────────────────────────────

    @Test
    fun `model count reads both wiring shapes`() {
        assertEquals(
            2,
            ProviderConnectionTest.modelCount("""{"data":[{"id":"a"},{"id":"b"}]}"""),
        )
        assertEquals(
            1,
            ProviderConnectionTest.modelCount("""{"models":[{"name":"gemini-2"}]}"""),
        )
    }

    @Test
    fun `a body that is not a model list yields null rather than a wrong number`() {
        assertNull(ProviderConnectionTest.modelCount("<html>blocked by proxy</html>"))
        assertNull(ProviderConnectionTest.modelCount(""))
        assertNull(ProviderConnectionTest.modelCount("""{"error":{"message":"nope"}}"""))
    }

    // ── outcome ─────────────────────────────────────────────────────────────

    @Test
    fun `only OK counts as success`() {
        assertTrue(
            ProviderConnectionTest.Outcome(ProviderConnectionTest.Kind.OK, 200, 10).ok,
        )
        assertFalse(
            ProviderConnectionTest.Outcome(ProviderConnectionTest.Kind.RATE_LIMITED, 429, 10).ok,
        )
        assertFalse(
            ProviderConnectionTest.Outcome(
                ProviderConnectionTest.Kind.HTTP_ERROR,
                0,
                10,
                transportError = "timeout",
            ).ok,
        )
    }
}
