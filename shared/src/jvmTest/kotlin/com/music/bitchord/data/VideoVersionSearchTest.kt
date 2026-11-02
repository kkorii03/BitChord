package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The video switch keeps its 1.8 guards: never search a blank row, never pick a take. */
class VideoVersionSearchTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        duration: String? = null,
        artistId: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        artistId = artistId,
    )

    private val target = TrackMatcher.Target(
        title = "After LIKE",
        artist = "IVE",
        durationSec = 192,
        artistId = "UC_IVE",
    )

    @Test
    fun `a row without a header is not searched`() {
        assertFalse(YtMusicRepository.videoVersionSearchWanted(song("V0", "", "IVE")))
        assertFalse(YtMusicRepository.videoVersionSearchWanted(song("V1", "After LIKE", "")))
        assertTrue(YtMusicRepository.videoVersionSearchWanted(song("V2", "After LIKE", "IVE")))
    }

    @Test
    fun `the video switch picks the official video, not a fancam or a stage`() {
        val fancam = song(
            "FANCAM00001",
            "[K-Fancam] \uC544\uC774\uBE0C \uC548\uC720\uC9C4 \uC9C1\uCEA0 After LIKE " +
                "(IVE YUJIN Fancam) | @MusicBank",
            "IVE",
            "3:12",
            "UC_IVE",
        )
        val official = song("OFFICIAL001", "After LIKE", "IVE", "3:12", "UC_IVE")

        assertEquals(official, YtMusicRepository.pickVideoVersion(listOf(fancam, official), target))
        assertNull(YtMusicRepository.pickVideoVersion(listOf(fancam), target))
        assertNull(
            YtMusicRepository.pickVideoVersion(
                listOf(song("STAGE000001", "After LIKE stage", "IVE", "3:12", "UC_IVE")),
                target,
            ),
        )
    }
}