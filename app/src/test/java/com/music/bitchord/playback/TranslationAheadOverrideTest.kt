package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationAheadOverrideTest {

    @Test
    fun `a per-song override becomes the prepared sheet without the file flag`() {
        val lines = listOf(LyricLine(0L, "첫 줄"), LyricLine(1_000L, "둘째 줄"))
        val stored = LyricsOverrideFile.Stored(
            lines = lines,
            source = null,
            savedAt = 1L,
            kind = LyricsOverrideFile.Kind.RECHECK,
        )

        val ahead = TranslationAhead.aheadFromOverride(stored)

        assertEquals(lines, ahead.lines)
        assertNull(ahead.source)
        assertNull(ahead.searchedAs)
        assertFalse(ahead.fromFile)
    }
}
