package com.music.bitchord.data.cover

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two callers sharing one in-flight cover lookup: the owner being cancelled
 * (its page closed, its own timeout fired) must not cancel the other caller,
 * such as the song-version audio switch waiting on the same row.
 */
class YouTubeCoverSharedLookupTest {

    private val song = Song(
        videoId = "vvvvvvvvvvv",
        title = "Shared Lookup Probe Song",
        artist = "Probe Artist",
        thumbnailUrl = "https://i.ytimg.com/vi/vvvvvvvvvvv/hqdefault.jpg",
        isVideo = true,
    )

    @BeforeTest
    fun setUp() {
        YouTubeCoverResolver.resetForTest()
        YouTubeCoverResolver.uncachedForTest = null
    }

    @AfterTest
    fun tearDown() {
        YouTubeCoverResolver.uncachedForTest = null
        YouTubeCoverResolver.resetForTest()
    }

    @Test
    fun `a waiter is not cancelled when the lookup's owner is`() = runBlocking {
        val ownerStarted = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        YouTubeCoverResolver.uncachedForTest = { _, _, _ ->
            if (calls.incrementAndGet() == 1) {
                ownerStarted.complete(Unit)
                awaitCancellation()
            }
            null
        }

        val owner = launch { YouTubeCoverResolver.matchFor(song) }
        withTimeout(5_000) { ownerStarted.await() }
        val waiter = async { YouTubeCoverResolver.matchFor(song) }
        repeat(3) { yield() } // the waiter reaches the shared lookup's await
        owner.cancelAndJoin()

        val outcome = runCatching { withTimeout(5_000) { waiter.await() } }
        assertFalse(waiter.isCancelled, "the owner's cancellation reached the waiter")
        assertTrue(outcome.isSuccess, "waiter failed: ${outcome.exceptionOrNull()}")
        assertNull(outcome.getOrNull())
        assertEquals(2, calls.get(), "the waiter looks the row up itself")
    }

    @Test
    fun `a waiter that is itself cancelled still stops`() = runBlocking {
        val ownerStarted = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        YouTubeCoverResolver.uncachedForTest = { _, _, _ ->
            calls.incrementAndGet()
            ownerStarted.complete(Unit)
            awaitCancellation()
        }

        val owner = launch { YouTubeCoverResolver.matchFor(song) }
        withTimeout(5_000) { ownerStarted.await() }
        val waiter = async { YouTubeCoverResolver.matchFor(song) }
        repeat(3) { yield() }
        waiter.cancelAndJoin()

        assertTrue(waiter.isCancelled)
        assertEquals(1, calls.get(), "a cancelled waiter must not start its own lookup")
        owner.cancelAndJoin()
    }
}
