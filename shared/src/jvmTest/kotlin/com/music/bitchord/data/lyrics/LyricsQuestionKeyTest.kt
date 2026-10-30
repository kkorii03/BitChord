package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A lyrics answer is only reusable for the question it answered. A length is
 * part of that question: a download row with none can settle on a different
 * edit than the player, which knows how long the recording is.
 */
class LyricsQuestionKeyTest {

    private fun key(
        durationMs: Long = 200_000L,
        album: String? = "Album",
        sources: Set<LyricsSource> = setOf(LyricsSource.LRCLIB),
        order: List<LyricsSource> = listOf(LyricsSource.LRCLIB),
        prioritize: Boolean = false,
    ) = LyricsRepository.questionKey(
        videoId = "video",
        title = "Song",
        artist = "Artist",
        durationMs = durationMs,
        album = album,
        prioritizeSyllableSync = prioritize,
        isrc = null,
        isVideo = false,
        artistKnown = true,
        alternatives = emptyList(),
        order = order,
        sources = sources,
    )

    @Test
    fun anUnknownLengthIsNotTheSameQuestionAsAKnownOne() {
        assertNotEquals(key(durationMs = 0L), key(durationMs = 200_000L))
    }

    @Test
    fun theCatalogueLengthAndTheDecoderLengthShareOneAnswer() {
        assertEquals(key(durationMs = 296_000L), key(durationMs = 297_500L))
    }

    @Test
    fun twoDifferentRecordingsDoNotShareAnAnswer() {
        assertNotEquals(key(durationMs = 200_000L), key(durationMs = 260_000L))
    }

    @Test
    fun aDifferentAlbumIsADifferentQuestion() {
        assertNotEquals(key(album = "Single"), key(album = "Live"))
    }
}
