package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The gated Home used to await its history, then Listen again, then mixes,
 * then likes, then the youtube.com home, then the Music channel. Those shelves
 * share no inputs, so a cold start paid their sum. These tests hold every
 * producer until all of them have started: a sequential implementation
 * deadlocks and fails the timeout.
 */
class FallbackHomeOverlapTest {

    @Test
    fun `independent fallback shelves start before any finishes`() = runBlocking {
        val started = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        fun markStarted() {
            if (started.incrementAndGet() == 6) allStarted.complete(Unit)
        }
        suspend fun waitForSiblings() {
            markStarted()
            allStarted.await()
        }

        val parts = withTimeout(2_000) {
            YouTubeFallback.loadFallbackHomeParts(
                history = {
                    waitForSiblings()
                    emptyList()
                },
                listenAgain = {
                    waitForSiblings()
                    null
                },
                myMixes = {
                    waitForSiblings()
                    null
                },
                liked = {
                    waitForSiblings()
                    null
                },
                youtubeHome = {
                    waitForSiblings()
                    emptyList<HomeShelf>() to null
                },
                musicChannel = {
                    waitForSiblings()
                    emptyList()
                },
            )
        }

        assertEquals(6, started.get(), "every independent shelf started before any awaited")
        assertEquals(emptyList(), parts.recentSongs)
        assertNull(parts.listenAgain)
        assertNull(parts.myMixes)
        assertNull(parts.liked)
        assertNull(parts.continuation)
        assertEquals(emptyList(), parts.youtubeHome)
        assertEquals(emptyList(), parts.musicChannel)
    }
}
