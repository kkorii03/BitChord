package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.LyricsTimingSource
import org.junit.Assert.assertTrue
import org.junit.Test

/** A cover row that lost its video flag is still timed as a video. */
class CoverLyricsVideoFlagTest {

    @Test
    fun aCoverRowWithoutTheVideoFlagIsStillAVideoForLyrics() {
        val row = Song(
            videoId = "cover",
            title = "에피톤 프로젝트 - 회전목마 / cover by 오화요(허니즈)",
            artist = "젤리의 노래저장소",
            thumbnailUrl = null,
            isVideo = false,
        )
        assertTrue(LyricsTimingSource.isVideo(row))
    }
}
