package com.music.bitchord.data.lyrics

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RG2-3: the cross-script stranger refusal (8358f8e05) reads the asked artist as the act.
 * A cover that never names its original is asked under its own channel, which is never the
 * record's artist: its translated original must still be found (lyrics retry under the
 * original's name, and the sync reference recording).
 */
class RecordingPickCoverChannelTest {

    private fun row(id: String, title: String, artist: String, length: String) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = length)

    private val original = listOf(row("o", "Good Day", "IU", "3:53"))

    @Test
    fun theMeasurementNameListOfACoverWithNoOriginalNamedFindsTheTranslation() {
        // LyricsController / LyricsTiming / TranslationAhead: "title to \"\"" when !artistKnown.
        val picked = RecordingPick.rank(
            listOf("좋은 날", "좋은 날"),
            listOf("", "Jane Kim"),
            original,
            240_000L,
        ).map { it.videoId }
        assertEquals(listOf("o"), picked)
    }

    @Test
    fun theLyricsRetryOfACoverWithNoOriginalNamedFindsTheTranslation() {
        // LyricsRepository.retryCatalogOriginal passes actKnown = artistKnown.
        val picked = RecordingPick.rank(
            listOf("좋은 날"),
            listOf("Jane Kim"),
            original,
            240_000L,
            actKnown = false,
        ).map { it.videoId }
        assertEquals(listOf("o"), picked)
    }

    @Test
    fun aStrangerIsStillRefusedWhenTheAskedArtistIsTheAct() {
        // 8358f8e05's case, unchanged: the act is named and the row is somebody else's.
        val picked = RecordingPick.rank(listOf("좋은 날"), listOf("Jane Kim"), original, 240_000L)
        assertEquals(emptyList(), picked.map { it.videoId })
    }
}
