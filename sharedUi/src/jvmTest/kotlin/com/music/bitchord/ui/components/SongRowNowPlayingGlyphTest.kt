package com.music.bitchord.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A playlist row marks the current track on its artwork with bars only while
 * it plays. Paused, there are no bars, so it keeps the trailing glyph — as it
 * did in 1.8. The 1.9 merge hid the glyph for every bar-styled row, leaving a
 * paused current track in a playlist with no marker at all.
 */
class SongRowNowPlayingGlyphTest {

    @Test
    fun `a paused playlist row keeps its now-playing glyph`() {
        assertTrue(showNowPlayingGlyph(isCurrent = true, trackNumber = null, searchPlayingStyle = true, isPlaying = false))
    }

    @Test
    fun `a playing playlist row leaves the glyph to its artwork bars`() {
        assertFalse(showNowPlayingGlyph(isCurrent = true, trackNumber = null, searchPlayingStyle = true, isPlaying = true))
    }

    @Test
    fun `a numbered album row marks the current track in its number slot`() {
        assertFalse(showNowPlayingGlyph(isCurrent = true, trackNumber = 3, searchPlayingStyle = true, isPlaying = false))
    }
}
