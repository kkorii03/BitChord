package com.music.bitchord.startup

import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The threads that open startup stores which do not have to be ready before the
 * first frame.
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
 * There are two threads. The encrypted stores share one Keystore master key,
 * and a first launch must not have two threads racing to create it, so they
 * stay serial on [encryptedExecutor]. Everything else — a large JSON parse, a
 * preference-file read — has no such constraint and runs on [plainExecutor], so
 * it no longer queues behind a slow Keystore open.
 */
object StartupWork {

    private const val TAG = "BitChord"

    /**
     * The stores that open an [com.music.bitchord.auth.EncryptedPrefs] file, and
     * therefore must share one thread. Telegram's chats and session marker use
     * the same encrypted file as its credentials.
     */
    private val encryptedStores = setOf("auth-store", "source-registry", "telegram-chats")

    private val encryptedExecutor: Executor = executor("startup-encrypted")
    private val plainExecutor: Executor = executor("startup-plain")

    private fun executor(name: String): Executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

    /** Opens [name]'s store off the main thread. A failure is logged, never thrown. */
    fun background(name: String, block: () -> Unit) {
        val executor = if (name in encryptedStores) encryptedExecutor else plainExecutor
        executor.execute {
            try {
                block()
            } catch (failure: Throwable) {
                TrackLog.w(TAG, "startup open failed: $name", failure)
            }
        }
    }

    /**
     * Restores the session on the serial encrypted thread, then attaches it
     * only after [settingsReady] and after [restore] has returned. Both run on
     * the same thread, so the attach can never race the restored cookie or
     * channel even if the settings read finishes first.
     */
    fun startSession(
        settingsReady: CountDownLatch,
        restore: () -> Unit,
        attach: () -> Unit,
    ) {
        background("auth-store", restore)
        background("auth-store") {
            settingsReady.await()
            attach()
        }
    }
}

/**
 * The session the startup thread restored, published once its cookie and
 * channel are in place.
 *
 * A reader that starts before that — the playback service in a process
 * without the UI, a stream resolve, a proxy publish — takes the value from
 * here or waits for it, instead of opening the Keystore on the main thread.
 */
object StartupSession {

    @Volatile
    private var published: AuthStore? = null

    @Volatile
    private var ready = CompletableDeferred<AuthStore?>()

    /** The published store, or null while the startup thread has not finished. */
    fun publishedOrNull(): AuthStore? = published

    /**
     * Completes when the cookie and channel are published. The value is null
     * when the auth open failed, so a caller can still resolve anonymously.
     */
    suspend fun awaitPublished(): AuthStore? = ready.await()

    internal fun publish(store: AuthStore?) {
        published = store
        ready.complete(store)
    }

    internal fun resetForTest() {
        published = null
        ready = CompletableDeferred()
    }
}
