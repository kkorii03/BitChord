package com.music.bitchord.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicVideoPictureFailureTest {

    @Test fun `a resolved picture clears its earlier failure`() {
        assertEquals(setOf("other"), clearPictureFailure(setOf("other", "loaded"), "loaded"))
    }
}
