package com.music.bitchord.ko

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UPG-speed item 2: the next song's YouTube URL is resolved once the current
 * song has played ~10 s on its own, never for a song skipped or scrubbed
 * before that, and the wait stops when the song is no longer next.
 * KoNextUrlAhead does not exist on the old code, so this class fails there.
 */
class KoNextUrlAheadTest {
    private val original = KoNextUrlAhead.resolve
    private val scope = CoroutineScope(SupervisorJob())

    @Before
    fun reset() = KoNextUrlAhead.reset()

    @After
    fun restore() {
        KoNextUrlAhead.reset()
        KoNextUrlAhead.resolve = original
        scope.cancel()
    }

    private fun tick(current: String, positionMs: Long, seekAt: Long = 0L, next: String = "B", video: String? = "vB") =
        KoNextUrlAhead.due(current, positionMs, seekAt, next, video)

    @Test
    fun resolvesOnceOnTheTickAtTenSeconds() {
        // The sampler ticks every five seconds from wherever the song began.
        assertFalse(tick("A", 300))
        assertFalse(tick("A", 5_300))
        assertTrue(tick("A", 10_290))
        assertFalse("one resolve per pair", tick("A", 15_300))
    }

    @Test
    fun aSongSkippedBeforeTenSecondsResolvesNothingAhead() {
        assertFalse(tick("A", 200))
        assertFalse(tick("A", 5_200))
        // Skipped to C at 7 s: C's own count starts from its first tick.
        assertFalse(tick("C", 3_000, next = "D", video = "vD"))
        assertFalse(tick("C", 8_000, next = "D", video = "vD"))
        assertTrue(tick("C", 13_000, next = "D", video = "vD"))
    }

    @Test
    fun aSeekStartsTheCountAgain() {
        assertFalse(tick("A", 0))
        assertFalse(tick("A", 5_000))
        // Scrubbed to 40 s before the 10 s tick.
        assertFalse(tick("A", 40_000, seekAt = 9_000))
        assertFalse(tick("A", 45_000, seekAt = 9_000))
        assertTrue(tick("A", 50_000, seekAt = 9_000))
    }

    @Test
    fun aJumpBackStartsTheCountAgain() {
        assertFalse(tick("A", 60_000))
        assertFalse(tick("A", 2_000))
        assertFalse(tick("A", 7_000))
        assertTrue(tick("A", 12_000))
    }

    @Test
    fun aRowWithNoYouTubeStreamIsNeverResolved() {
        assertFalse(tick("A", 0, video = null))
        assertFalse(tick("A", 10_000, video = null))
        assertFalse(tick("A", 15_000, video = ""))
    }

    @Test
    fun onTickResolvesTheNextStreamIdAndDropStopsTheWait() = runBlocking {
        val asked = CompletableDeferred<String>()
        val stopped = CompletableDeferred<Unit>()
        KoNextUrlAhead.resolve = { id ->
            asked.complete(id)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        KoNextUrlAhead.onTick(scope, "A", 0, 0L, "B", "vB")
        KoNextUrlAhead.onTick(scope, "A", 10_000, 0L, "B", "vB")
        assertEquals("vB", withTimeout(5_000) { asked.await() })
        // Crossfading into it, or it starting, keeps it.
        KoNextUrlAhead.keepOnly(setOf("A", "B"))
        assertFalse(stopped.isCompleted)
        // The queue moved: B is neither playing nor next.
        KoNextUrlAhead.keepOnly(setOf("A", "X"))
        withTimeout(5_000) { stopped.await() }
        assertTrue(stopped.isCompleted)
    }
}
