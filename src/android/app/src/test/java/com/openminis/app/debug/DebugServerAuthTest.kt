package com.openminis.app.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL] The auth gate contract.
 *
 * This file used to assert the opposite for loopback: an `adb forward` client
 * was exempt "so the local tooling keeps working unchanged". That exemption
 * was the hole. Every installed app can connect to 127.0.0.1 without any
 * permission, and the surface behind it includes `provider.export` (every
 * stored API key), screenshots, taps and shell execution — so "loopback" meant
 * "anything else on the phone", not "the developer".
 *
 * The contract now: the exact per-install token is required on every
 * connection, loopback or not. `isLoopback` only reaches the log line that
 * names the refusal.
 */
class DebugServerAuthTest {

    private val token = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4"

    @Test
    fun `loopback without a token is rejected`() {
        assertFalse(DebugServer.isAuthorized(isLoopback = true, providedToken = null, expectedToken = token))
        assertFalse(DebugServer.isAuthorized(isLoopback = true, providedToken = "", expectedToken = token))
        assertFalse(DebugServer.isAuthorized(isLoopback = true, providedToken = "wrong", expectedToken = token))
    }

    @Test
    fun `loopback with the token is accepted`() {
        assertTrue(DebugServer.isAuthorized(isLoopback = true, providedToken = token, expectedToken = token))
    }

    @Test
    fun `remote without token is rejected`() {
        assertFalse(DebugServer.isAuthorized(isLoopback = false, providedToken = null, expectedToken = token))
        assertFalse(DebugServer.isAuthorized(isLoopback = false, providedToken = "", expectedToken = token))
    }

    @Test
    fun `remote with wrong token is rejected`() {
        assertFalse(DebugServer.isAuthorized(isLoopback = false, providedToken = "deadbeef", expectedToken = token))
        // Same length, one char off — the constant-time compare must still reject.
        assertFalse(
            DebugServer.isAuthorized(
                isLoopback = false,
                providedToken = token.dropLast(1) + "X",
                expectedToken = token,
            ),
        )
    }

    @Test
    fun `remote with correct token is accepted`() {
        assertTrue(DebugServer.isAuthorized(isLoopback = false, providedToken = token, expectedToken = token))
    }

    @Test
    fun `an empty expected token never authorizes anything`() {
        // Defensive: a failed token write must not open the gate — and this now
        // holds on loopback too, which is the case that used to slip through.
        assertFalse(DebugServer.isAuthorized(isLoopback = false, providedToken = "", expectedToken = ""))
        assertFalse(DebugServer.isAuthorized(isLoopback = false, providedToken = "x", expectedToken = ""))
        assertFalse(DebugServer.isAuthorized(isLoopback = true, providedToken = "", expectedToken = ""))
        assertFalse(DebugServer.isAuthorized(isLoopback = true, providedToken = "x", expectedToken = ""))
    }
}
