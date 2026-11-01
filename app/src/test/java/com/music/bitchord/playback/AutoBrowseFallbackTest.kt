package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A playlist or browse fetch that timed out used to produce no rows, so Android
 * Auto showed the same empty folder it shows for a playlist with no songs. A
 * failed load has to become a row the driver can see and retry by reopening.
 */
class AutoBrowseFallbackTest {

    @Test
    fun `a failed playlist browse is not drawn as an empty folder`() {
        val shown = AutoBrowseFallback.items(emptyList<String>()) {
            "msg:unavailable|playlist:abc"
        }

        assertEquals(listOf("msg:unavailable|playlist:abc"), shown)
    }

    @Test
    fun `a successful browse keeps every song and adds nothing`() {
        val shown = AutoBrowseFallback.items(listOf("a", "b")) {
            "msg:unavailable|browse:xyz"
        }

        assertEquals(listOf("a", "b"), shown)
    }
}
