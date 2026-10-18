package com.music.bitchord.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleMusicStyleTest {
    @Test
    fun rowThumbnailsUseTheSmallArtworkCorner() {
        assertEquals(SMALL_ART_CORNER, artworkCorner(48.dp))
        assertEquals(SMALL_ART_CORNER, artworkCorner(72.dp))
        assertEquals(8f, artworkCorner(72.dp).value, 0f)
    }

    @Test
    fun cardSizedArtworkUsesTheLargeCardCorner() {
        assertEquals(LARGE_CARD_CORNER, artworkCorner(88.dp))
        assertEquals(LARGE_CARD_CORNER, artworkCorner(300.dp))
        assertEquals(12f, artworkCorner(300.dp).value, 0f)
    }

    @Test
    fun standardTrackRowsShareOneVerticalPadding() {
        assertEquals(4f, LIST_ROW_VERTICAL_PADDING.value, 0f)
    }
}
