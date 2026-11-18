package com.music.bitchord.ko

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * log1819: 11-18 Safe and Sound jWpZuFM0Qtk. The current song TOMBOY swapped to its FLAC
 * at 26.6 s; the 30 s message on the old item never came, the paid-sync wait ran out at
 * 18:05:27 (= its deadline), and the next row played with no sync.
 */
class KoAiGateAfterSwapTest {

    @Test
    fun theSameSongPastThirtySecondsOpensTheGateAtTheDeadline() {
        assertTrue(KoAiGateAfterSwap.opens("zC8RrOA0spo", "zC8RrOA0spo", 35_000L))
        assertTrue(KoAiGateAfterSwap.openAfterTimeout("zC8RrOA0spo", "zC8RrOA0spo", 30_000L))
    }

    @Test
    fun aSkippedPausedOrUnknownSongKeepsItShut() {
        // Skipped to another song: the gate is for the song that was playing.
        assertFalse(KoAiGateAfterSwap.opens("zC8RrOA0spo", "gXLzCTQ76I4", 40_000L))
        // Paused or sought back: still short of 30 s.
        assertFalse(KoAiGateAfterSwap.opens("zC8RrOA0spo", "zC8RrOA0spo", 29_999L))
        assertFalse(KoAiGateAfterSwap.opens(null, null, 40_000L))
        assertFalse(KoAiGateAfterSwap.opens("zC8RrOA0spo", null, 40_000L))
    }

    @Test
    fun theWaitAsksAgainWhenItsMessageNeverCame() {
        val prep = File("src/main/java/com/music/bitchord/playback/NextTrackPreparation.kt").readText()
        val wait = prep.substringAfter("private suspend fun awaitAiGate(").substringBefore("\n    }")
        assertTrue(wait.contains("val startedId = player.currentMediaItem?.mediaId"))
        assertTrue(wait.contains("KoAiGateAfterSwap.openAfterTimeout(startedId, now?.currentMediaItem?.mediaId, now?.currentPosition ?: 0L)"))
        // Still the player's own message first, with its deadline: no polling.
        assertTrue(wait.contains("withTimeoutOrNull(waitMs + AI_GATE_GRACE_MS) { reached.await() } != null ||"))
        assertTrue(wait.contains("message.cancel()"))
    }
}
