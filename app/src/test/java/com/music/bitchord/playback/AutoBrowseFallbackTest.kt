package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A playlist or browse fetch that timed out used to produce no rows, so Android
 * Auto showed the same empty folder it shows for a playlist with no songs. A
 * failed load has to become a row the driver can see and retry by reopening.
 *
 * A successful fetch that simply has no songs is a different answer: it must
 * not claim the folder could not be loaded, because reopening it can never
 * make it non-empty.
 */
class AutoBrowseFallbackTest {

    @Test
    fun `a failed playlist browse is not drawn as an empty folder`() {
        val shown = AutoBrowseFallback.items(
            songs = null,
            unavailable = { "msg:unavailable|playlist:abc" },
            empty = { "msg:empty|playlist:abc" },
        )

        assertEquals(listOf("msg:unavailable|playlist:abc"), shown)
    }

    @Test
    fun `a successful browse with no songs is an ordinary empty folder`() {
        val shown = AutoBrowseFallback.items(
            songs = emptyList<String>(),
            unavailable = { "msg:unavailable|browse:xyz" },
            empty = { "msg:empty|browse:xyz" },
        )

        assertEquals(listOf("msg:empty|browse:xyz"), shown)
    }

    @Test
    fun `a successful browse keeps every song and adds nothing`() {
        val shown = AutoBrowseFallback.items(
            songs = listOf("a", "b"),
            unavailable = { "msg:unavailable|browse:xyz" },
            empty = { "msg:empty|browse:xyz" },
        )

        assertEquals(listOf("a", "b"), shown)
    }
}
