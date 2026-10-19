package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The video-switch matcher: official videos beat fancams, stages and covers. */
class VideoSwitchMatcherTest {

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
    fun `a fancam cannot win over the official video`() {
        val fancam = song(
            "FANCAM00001",
            "[K-Fancam] \uC544\uC774\uBE0C \uC548\uC720\uC9C4 \uC9C1\uCEA0 After LIKE " +
                "(IVE YUJIN Fancam) | @MusicBank",
            "IVE",
            "3:12",
            "UC_IVE",
        )
        val official = song("OFFICIAL001", "After LIKE", "IVE", "3:12", "UC_IVE")

        assertEquals(
            official,
            TrackMatcher.bestForVideoSwitch(listOf(fancam, official), target),
        )
    }

    @Test
    fun `an official title wins between two otherwise valid rows`() {
        val plain = song("PLAIN000001", "After LIKE", "IVE", "3:12")
        val official = song("OFFICIAL002", "After LIKE (Official Video)", "IVE", "3:12", "UC_IVE")

        assertEquals(
            official,
            TrackMatcher.bestForVideoSwitch(listOf(plain, official), target),
        )
    }

    @Test
    fun `a stage or cover is skipped when the original title does not name it`() {
        val stage = song("STAGE000001", "After LIKE stage", "IVE", "3:12", "UC_IVE")
        val cover = song("COVER000001", "After LIKE cover", "Someone", "3:12")

        assertNull(TrackMatcher.bestForVideoSwitch(listOf(stage, cover), target))
    }

    @Test
    fun `a marker is allowed when the requested title names it`() {
        val live = TrackMatcher.Target(title = "After LIKE (Live)", artist = "IVE")
        val candidate = song("LIVE0000001", "After LIKE (Live)", "IVE", "3:12", "UC_IVE")

        assertNotNull(TrackMatcher.bestForVideoSwitch(listOf(candidate), live))
    }

    @Test
    fun `the audio switch never lands on another artist's song of the same name`() {
        val bieber = TrackMatcher.Target(title = "Sorry", artist = "Justin Bieber", durationSec = 200)
        val fx = song("FXSORRY0001", "Sorry (Dear. Daddy)", "f(x)", "3:21")
        val other = song("OTHERSORRY1", "Sorry", "Beyonce", "3:20")
        assertNull(TrackMatcher.bestSameArtist(listOf(fx, other), bieber))
        val right = song("BIEBERSORRY", "Sorry", "Justin Bieber", "3:20")
        assertEquals("BIEBERSORRY", TrackMatcher.bestSameArtist(listOf(other, right, fx), bieber)?.videoId)
    }
}