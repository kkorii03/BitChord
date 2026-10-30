package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.ui.LyricsLoadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Changing a lyrics setting must make the current track look itself up again. */
class LyricsLoadKeyTest {

    private fun key(
        sources: Set<LyricsSource> = setOf(LyricsSource.LRCLIB, LyricsSource.KUGOU),
        order: List<LyricsSource> = listOf(LyricsSource.LRCLIB, LyricsSource.KUGOU),
        prioritize: Boolean = false,
        localUri: String? = null,
        isVideo: Boolean = false,
    ) = LyricsLoadKey.of(
        videoId = "video",
        isVideo = isVideo,
        localUri = localUri,
        sources = sources,
        order = order,
        prioritizeSyllableSync = prioritize,
    )

    @Test
    fun theSameSettingsAreTheSameQuestion() {
        assertEquals(key(), key())
    }

    @Test
    fun changingTheSourceOrderReloadsTheCurrentTrack() {
        assertNotEquals(key(), key(order = listOf(LyricsSource.KUGOU, LyricsSource.LRCLIB)))
    }

    @Test
    fun changingSyllablePriorityReloadsTheCurrentTrack() {
        assertNotEquals(key(), key(prioritize = true))
    }

    @Test
    fun switchingASourceOffReloadsTheCurrentTrack() {
        assertNotEquals(key(), key(sources = setOf(LyricsSource.LRCLIB)))
    }

    @Test
    fun aDownloadedFileAndItsStreamAreDifferentQuestions() {
        assertNotEquals(key(), key(localUri = "file:///song.flac"))
    }

    @Test
    fun aVideoAndASongStreamAreDifferentQuestions() {
        assertNotEquals(key(), key(isVideo = true))
    }
}
