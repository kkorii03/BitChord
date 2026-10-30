package com.music.bitchord.ui.player

import com.music.bitchord.data.settings.LastPlayerScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class LastPlayerScreenPolicyTest {

    @Test
    fun `lyrics wins over queue when both flags are set`() {
        assertEquals(LastPlayerScreen.LYRICS, lastPlayerScreenOf(lyricsOpen = true, queueOpen = true))
    }

    @Test
    fun `queue is recorded only when lyrics is closed`() {
        assertEquals(LastPlayerScreen.QUEUE, lastPlayerScreenOf(lyricsOpen = false, queueOpen = true))
    }

    @Test
    fun `closing both panels records the main player`() {
        assertEquals(LastPlayerScreen.MAIN, lastPlayerScreenOf(lyricsOpen = false, queueOpen = false))
    }
}
