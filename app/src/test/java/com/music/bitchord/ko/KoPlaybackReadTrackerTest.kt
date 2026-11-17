package com.music.bitchord.ko

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Review FX2 C2b-2: a release landing between [KoPlaybackReadTracker.awaitRelease]'s
 * check and its install lost the wake-up, and the waiter (the whole-rendition
 * fetch, the lower-copy cleanup) waited forever.
 */
class KoPlaybackReadTrackerTest {

    @Test
    fun `a release between the check and the install still wakes the waiter`() {
        val tracker = KoPlaybackReadTracker()
        val key = "abc#hifi"
        tracker.retainKey(key)
        var releaser: Thread? = null
        tracker.beforeInstall = {
            tracker.beforeInstall = {}
            // The loader thread lets go of the key right now, while the waiter
            // is between "the player holds it" and installing its signal.
            val thread = Thread {
                tracker.releaseKey(key)
                tracker.readerReleased(key)
            }
            releaser = thread
            thread.start()
            // Unlocked (the bug), the releaser finishes here and finds nothing
            // to complete. Locked (the fix), it waits for this block to end.
            thread.join(500)
        }

        val woke = runBlocking { withTimeoutOrNull(2_000) { tracker.awaitRelease(key) } }

        releaser?.join(2_000)
        assertNotNull("awaitRelease never woke after the player let go of the key", woke)
        assertFalse(tracker.playerHoldsKey(key))
    }

    @Test
    fun `a key nobody holds returns at once`() {
        val tracker = KoPlaybackReadTracker()
        val woke = runBlocking { withTimeoutOrNull(1_000) { tracker.awaitRelease("free") } }
        assertNotNull(woke)
    }

    @Test
    fun `an ordinary release wakes the waiter`() {
        val tracker = KoPlaybackReadTracker()
        tracker.retainKey("k")
        val woke = runBlocking {
            withTimeoutOrNull(2_000) {
                coroutineScope {
                    val wait = async { tracker.awaitRelease("k") }
                    kotlinx.coroutines.yield()
                    tracker.releaseKey("k")
                    tracker.readerReleased("k")
                    wait.await()
                }
            }
        }
        assertNotNull(woke)
    }
}
