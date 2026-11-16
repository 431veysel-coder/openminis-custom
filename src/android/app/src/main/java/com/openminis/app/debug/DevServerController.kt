package com.openminis.app.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [MINIS-OZEL] Owns the lifetime of the local API server.
 *
 * ## The bug this fixes
 *
 * The server used to be started once from `MinisApp.onCreate` and then lived
 * for the whole process. Turning the switch in Settings did nothing until the
 * app was killed and reopened — which is exactly the wrong behaviour for a
 * switch whose whole job is to close a socket: a user who turns it off expects
 * the port to be closed *now*, not after the next cold start. (Observed: switch
 * on, `127.0.0.1:5321` still refused, because the process had been running
 * since before the switch existed.)
 *
 * ## The second thing this fixes
 *
 * Leaving it open forever is not what "I turned it on to look at something"
 * means. Every start arms a [TIMEOUT_MS] idle clock; the clock is not extended
 * by activity, deliberately — this is a development surface, not a service, and
 * a timeout that slides with use is a timeout that never fires during the very
 * session that forgot to close it. Turning it on again rearms it.
 *
 * ## What the timeout is and is not
 *
 * Not a security boundary — the token is. It is a floor under the blast radius:
 * a switch left on by accident stops being an open port within the hour.
 */
object DevServerController {

    private const val TAG = "DevServer"

    /** How long a start lasts before the socket closes itself. */
    const val TIMEOUT_MINUTES = 30
    private const val TIMEOUT_MS = TIMEOUT_MINUTES * 60_000L

    /** What Settings renders. */
    data class State(
        val running: Boolean = false,
        val address: String = "",
        val token: String = "",
        /** Wall-clock ms when the socket closes itself, or null. */
        val closesAt: Long? = null,
        /** Last failure, shown instead of a silent no-op. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var server: DebugServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopTask = Runnable { stop(reason = "timeout") }

    @Synchronized
    fun start(context: Context) {
        val ctx = context.applicationContext
        if (server != null) {
            Log.i(TAG, "already running")
            return
        }
        val srv = DebugServer(ctx)
        server = srv
        try {
            srv.start(onStopped = { clearIf(srv) })
        } catch (t: Throwable) {
            server = null
            _state.value = State(error = t.message ?: t.javaClass.simpleName)
            return
        }
        val closesAt = System.currentTimeMillis() + TIMEOUT_MS
        handler.removeCallbacks(stopTask)
        handler.postDelayed(stopTask, TIMEOUT_MS)
        _state.value = State(
            running = true,
            address = srv.address,
            token = srv.token(),
            closesAt = closesAt,
        )
        Log.i(TAG, "started on ${srv.address}, auto-close in ${TIMEOUT_MINUTES}m")
    }

    @Synchronized
    fun stop(reason: String = "user") {
        handler.removeCallbacks(stopTask)
        val srv = server ?: run {
            _state.value = State()
            return
        }
        server = null
        srv.stop()
        _state.value = State()
        Log.i(TAG, "stopped ($reason)")
    }

    /** Applies the persisted switch. Called on launch and whenever it flips. */
    @Synchronized
    fun applySwitch(context: Context, enabled: Boolean) {
        if (enabled) start(context) else stop(reason = "switch")
    }

    /** The server stopped on its own (bind failure, socket closed elsewhere). */
    @Synchronized
    private fun clearIf(srv: DebugServer) {
        if (server !== srv) return
        server = null
        handler.removeCallbacks(stopTask)
        _state.value = State()
    }

    /** Mirrors [state] for a caller that cannot collect a Flow. */
    fun snapshot(): State = _state.value
}
