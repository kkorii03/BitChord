package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertTrue
import org.junit.Test

/** An offline playlist row keeps the upload type it was added with. */
class OfflinePlaylistsVideoTest {

    @Test
    fun `a video row keeps its flag when stored`() {
        val stored = OfflinePlaylists::class.java
            .getDeclaredMethod("stored", Song::class.java)
            .apply { isAccessible = true }
            .invoke(
                OfflinePlaylists,
                Song(videoId = "mv1", title = "Some MV", artist = "Channel", thumbnailUrl = null, isVideo = true),
            ) as Song

        assertTrue(stored.isVideo)
        assertTrue(stored.isVideoOrigin)
    }
}
