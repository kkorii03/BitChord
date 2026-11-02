package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Clean/radio labels name the ordinary release, not a different take. */
class LyricsVersionNeutralTest {

    private val plain = LrcRed.Hit(
        isrc = "USRC17607839",
        title = "Song",
        artist = "Artist",
        duration = 200.0,
    )

    private fun best(title: String) = LrcRed.best(listOf(plain), title, "Artist", 200_000L)?.isrc

    @Test
    fun cleanAndRadioLabelsKeepTheSameRecording() {
        assertEquals("USRC17607839", best("Song (Clean Edit)"))
        assertEquals("USRC17607839", best("Song (Clean Version)"))
        assertEquals("USRC17607839", best("Song (Radio Edit)"))
    }

    @Test
    fun aHitWithoutALengthIsRefusedWhenTheSongLengthIsKnown() {
        val unknown = LrcRed.Hit(isrc = "USRC17607839", title = "Song", artist = "Artist", duration = null)
        assertNull(LrcRed.best(listOf(unknown), "Song", "Artist", 200_000L))
    }

    @Test
    fun aRealVersionStillSeparatesThem() {
        assertNull(best("Song (Live)"))
        assertNull(best("Song (Remix)"))
    }
}
