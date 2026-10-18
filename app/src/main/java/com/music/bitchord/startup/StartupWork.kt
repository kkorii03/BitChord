package com.music.bitchord.startup

import com.music.bitchord.data.TrackLog
import java.util.concurrent.Executors

/**
 * The one thread that opens startup stores which do not have to be ready
 * before the first frame.
 *
 * It used to be that every store the app owns was opened on the main thread
 * before `Application.onCreate` returned, and the thread that opened the
 * slowest of them — the encrypted source registry — was then joined onto the
 * main thread so nothing could run against a half-open store. Both are gone:
 * the opens run here, and a store that is asked for before it finished opens
 * itself on the asking thread ([com.music.bitchord.data.sources.SourceRegistry]
 * and friends keep that fallback), so a caller is never left holding an empty
 * store.
 *
 * One thread rather than a pool: the encrypted stores share a single Keystore
 * master key, and a first launch must not have two threads racing to create it.
 * Serialising the opens here is what makes that impossible.
 */
object StartupWork {

    private const val TAG = "BitChord"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "startup-init").apply { isDaemon = true }
    }

    /** Opens [name]'s store off the main thread. A failure is logged, never thrown. */
    fun background(name: String, block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (failure: Throwable) {
                TrackLog.w(TAG, "startup open failed: $name", failure)
            }
        }
    }
}
