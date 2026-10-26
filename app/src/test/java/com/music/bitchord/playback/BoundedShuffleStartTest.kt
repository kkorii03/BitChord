package com.music.bitchord.playback

import com.music.bitchord.data.model.BrowseType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedShuffleStartTest {

    @Test
    fun `remote album playlist and mix pages continue after the first page`() {
        listOf(BrowseType.ALBUM, BrowseType.PLAYLIST, BrowseType.OTHER).forEach { type ->
            assertTrue(
                "$type should continue",
                BoundedShuffleStart.canContinue(
                    browseId = "VLmix",
                    type = type,
                    preview = false,
                    casting = false,
                    continuation = "token",
                ),
            )
        }
    }

    @Test
    fun `an artist page is not a bounded running order`() {
        assertFalse(
            BoundedShuffleStart.canContinue(
                browseId = "UCartist",
                type = BrowseType.ARTIST,
                preview = false,
                casting = false,
                continuation = "token",
            ),
        )
    }

    @Test
    fun `local pages previews casts and pages without a continuation take the full path`() {
        assertFalse(BoundedShuffleStart.canContinue("local:downloads", BrowseType.PLAYLIST, false, false, "token"))
        assertFalse(BoundedShuffleStart.canContinue("VLmix", BrowseType.PLAYLIST, preview = true, casting = false, continuation = "token"))
        assertFalse(BoundedShuffleStart.canContinue("VLmix", BrowseType.PLAYLIST, preview = false, casting = true, continuation = "token"))
        assertFalse(BoundedShuffleStart.canContinue("VLmix", BrowseType.PLAYLIST, preview = false, casting = false, continuation = null))
        assertFalse(BoundedShuffleStart.canContinue("VLmix", BrowseType.PLAYLIST, preview = false, casting = false, continuation = "  "))
        assertFalse(BoundedShuffleStart.canContinue(null, BrowseType.PLAYLIST, preview = false, casting = false, continuation = "token"))
        assertFalse(BoundedShuffleStart.canContinue("", BrowseType.PLAYLIST, preview = false, casting = false, continuation = "token"))
    }
}
