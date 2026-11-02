package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.ui.LyricsLoadKey
import com.music.bitchord.ui.LyricsRequestAction
import com.music.bitchord.ui.LyricsRequestGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every track waits for the player's length before the network lookup is
 * claimed; claiming without it dropped the re-trigger that carried the length,
 * and a downloaded file got no request at all (a59AuyMjb68). A local file may
 * still show its own lyrics before the length arrives.
 */
class LyricsRequestGateTest {

    private fun key(localFile: Boolean) = LyricsLoadKey.of(
        videoId = "a59AuyMjb68",
        isVideo = false,
        localUri = if (localFile) "content://song.a59AuyMjb68" else null,
        sources = setOf(LyricsSource.LRCLIB),
        order = listOf(LyricsSource.LRCLIB),
        prioritizeSyllableSync = false,
    )

    @Test
    fun aStreamWaitsForAKnownLengthThenLooksUpExactlyOnce() {
        val gate = LyricsRequestGate()
        val key = key(localFile = false)
        assertEquals(LyricsRequestAction.WAIT_FOR_DURATION, gate.begin(key, 0L, hasLocalFile = false))
        assertFalse(gate.lookupClaimed)
        assertEquals(LyricsRequestAction.LOOKUP, gate.begin(key, 215_000L, hasLocalFile = false))
        assertTrue(gate.lookupClaimed)
        // The same track and settings, with or without a slightly different
        // length, must not start a second request.
        assertEquals(LyricsRequestAction.ALREADY_LOADED, gate.begin(key, 215_000L, hasLocalFile = false))
        assertEquals(LyricsRequestAction.ALREADY_LOADED, gate.begin(key, 219_000L, hasLocalFile = false))
    }

    @Test
    fun aLocalFilePreviewsItsOwnLyricsWithoutALengthThenLooksUpOnceWhenItArrives() {
        val gate = LyricsRequestGate()
        val key = key(localFile = true)
        assertEquals(LyricsRequestAction.LOCAL_PREVIEW, gate.begin(key, 0L, hasLocalFile = true))
        assertFalse(gate.lookupClaimed)
        // A repeat before the length is not a second preview.
        assertEquals(LyricsRequestAction.ALREADY_LOADED, gate.begin(key, 0L, hasLocalFile = true))
        assertEquals(LyricsRequestAction.LOOKUP, gate.begin(key, 215_000L, hasLocalFile = true))
        assertTrue(gate.lookupClaimed)
        assertEquals(LyricsRequestAction.ALREADY_LOADED, gate.begin(key, 215_000L, hasLocalFile = true))
    }

    @Test
    fun aMissReleasesTheClaimForALaterRetry() {
        val gate = LyricsRequestGate()
        val key = key(localFile = false)
        assertEquals(LyricsRequestAction.LOOKUP, gate.begin(key, 215_000L, hasLocalFile = false))
        gate.release()
        assertFalse(gate.lookupClaimed)
        assertEquals(LyricsRequestAction.LOOKUP, gate.begin(key, 215_000L, hasLocalFile = false))
    }
}
