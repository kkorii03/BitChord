package com.music.bitchord.data.spotify

import org.junit.Assert.assertTrue
import org.junit.Test

/** A video row stays a video through the local playlist file. */
class LocalPlaylistVideoFlagTest {

    @Test
    fun `a video row stays a video after the local playlist round trip`() {
        val parsed = parsePlaylists(
            """[{"id":"p1","title":"List","songs":[
                {"videoId":"video-1","title":"MV","artist":"Artist","isVideo":true}
            ]}]""",
        )

        assertTrue(parsed!!.playlists.single().songs.single().isVideo)
    }

    @Test
    fun `an old cover row without the flag is still a video`() {
        val parsed = parsePlaylists(
            """[{"id":"p1","title":"List","songs":[
                {"videoId":"cover-1","title":"아이묭 - Marigold / cover by 오화요","artist":"Ohwayo"}
            ]}]""",
        )

        assertTrue(parsed!!.playlists.single().songs.single().isVideo)
    }
}
