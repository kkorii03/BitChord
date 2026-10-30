package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio switch on a soundtrack upload.
 *
 * A fan upload of a soundtrack track is titled with the song, the credit the
 * uploader gives it and the work it comes from — "Cream - White Room | Joker
 * OST". Read as an ordinary title it is the uploader's head, "Cream", and the
 * catalogue row can never be the same recording: that is why a whole
 * soundtrack playlist played as video and no song version was ever found.
 */
class AudioSwitchWorkTitleTest {

    private fun upload(id: String, title: String, artist: String, duration: String) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        isVideo = true,
    )

    private fun catalogue(id: String, title: String, artist: String, duration: String) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    @Test
    fun `a soundtrack upload is asked for by its song and its credit`() {
        val row = upload(
            "UPLOAD00001",
            "Cream - White Room | Joker OST",
            "Joker OST Playlist",
            "4:19",
        )
        val plan = TrackMatcher.audioSwitchPlan(row)
        assertEquals("White Room", plan.target.title)
        assertEquals("Cream", plan.target.artist)
        assertEquals(listOf("white room cream", "white room"), plan.queries)
    }

    @Test
    fun `the catalogue song of a soundtrack upload is found`() {
        val row = upload(
            "UPLOAD00002",
            "Cream - White Room | Joker OST",
            "Joker OST Playlist",
            "4:19",
        )
        val plan = TrackMatcher.audioSwitchPlan(row)
        val song = catalogue("CATALOGUE01", "White Room", "Cream", "4:13")
        assertEquals(
            "CATALOGUE01",
            TrackMatcher.bestSameArtist(listOf(song), plan.target)?.videoId,
        )
    }

    @Test
    fun `another artist's song of the same name is still refused`() {
        val row = upload(
            "UPLOAD00003",
            "Cream - White Room | Joker OST",
            "Joker OST Playlist",
            "4:19",
        )
        val plan = TrackMatcher.audioSwitchPlan(row)
        val other = catalogue("OTHER00001", "White Room", "The Kills", "4:13")
        assertNull(TrackMatcher.bestSameArtist(listOf(other), plan.target))
        assertEquals("artist", TrackMatcher.songVersionRejectReason(other, plan.target))
    }

    @Test
    fun `a soundtrack upload with no credit is asked for by the work`() {
        val row = upload(
            "UPLOAD00004",
            "Following Sophie | Joker OST",
            "Joker OST Playlist",
            "3:04",
        )
        val plan = TrackMatcher.audioSwitchPlan(row)
        assertEquals("Following Sophie", plan.target.title)
        assertEquals(
            listOf("following sophie joker soundtrack", "following sophie"),
            plan.queries,
        )
        assertTrue(plan.queries.none { it.contains("Playlist") })
    }

    @Test
    fun `an ordinary upload is asked for as it always was`() {
        val row = upload(
            "UPLOAD00005",
            "After LIKE (Official Music Video)",
            "IVE",
            "3:12",
        )
        val plan = TrackMatcher.audioSwitchPlan(row)
        assertEquals("After LIKE (Official Music Video)", plan.target.title)
        assertEquals("IVE", plan.target.artist)
        assertEquals(listOf("after like ive", "after like"), plan.queries)
    }
}
