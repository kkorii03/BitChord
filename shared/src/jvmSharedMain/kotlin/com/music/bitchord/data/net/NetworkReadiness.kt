package com.music.bitchord.data.net

import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.CompletableDeferred

/**
 * The one signal that says the stored login cookie and the proxy credentials
 * have finished loading.
 *
 * Startup opens the encrypted credential store and restores the session off the
 * main thread. A request that starts before that finishes must not go out as an
 * anonymous one — the listener's own recommendations and play history would be
 * credited to nobody — and must not go out through a proxy route whose password
 * has not been read yet. So every network path awaits this before it builds its
 * request, which lets the cookie and route fields be read from their finished
 * values rather than from the defaults.
 *
 * A startup that fails still opens the gate: the request then proceeds
 * anonymously, which is what the old blocking startup did on an empty store,
 * rather than leaving the first request waiting forever. The failure is logged
 * exactly once.
 */
object NetworkReadiness {

    private const val TAG = "BitChord"

    @Volatile
    private var gate = CompletableDeferred<Unit>()

    /** Whether startup has finished preparing credentials, successfully or not. */
    val isReady: Boolean get() = gate.isCompleted

    /** Waits for the one credential-loading pass. Never polls. */
    suspend fun await() {
        gate.await()
    }

    /** Opens the gate after the cookie and proxy credentials are in place. */
    fun markReady() {
        gate.complete(Unit)
    }

    /**
     * Opens the gate after preparation failed, so requests proceed anonymously
     * instead of hanging. Logs the reason once.
     */
    fun markFailed(reason: String) {
        if (gate.complete(Unit)) {
            Log.w(TAG, "startup credentials unavailable; continuing anonymously: $reason")
        }
    }

    /** Test seam: starts a fresh, closed gate. */
    fun resetForTests() {
        gate = CompletableDeferred()
    }
}
