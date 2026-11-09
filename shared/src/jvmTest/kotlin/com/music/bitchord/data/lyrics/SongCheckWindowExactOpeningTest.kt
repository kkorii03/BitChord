package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

class SongCheckWindowExactOpeningTest {
    @Test
    fun `rough heard opening asks exact unless exact fit is kept`() {
        assertEquals(true, SongCheckWindow.videoExactOpening(true, true, false))
        assertEquals(false, SongCheckWindow.videoExactOpening(false, true, false))
        assertEquals(false, SongCheckWindow.videoExactOpening(true, false, false))
        assertEquals(false, SongCheckWindow.videoExactOpening(true, true, true))
    }
}
