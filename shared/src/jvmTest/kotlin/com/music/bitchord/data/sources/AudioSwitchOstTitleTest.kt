package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fan uploads of a soundtrack print the work and its "OST" label after the
 * song, and often put the performer before it. The audio switch has to search
 * the song's own title and credit, not that packaging or the uploader.
 */
class AudioSwitchOstTitleTest {

    private fun identity(title: String, artist: String = "Joker OST") =
        TrackMatcher.audioSwitchIdentity(title, artist)

    @Test
    fun `an artist and title before a Joker OST tail are split`() {
        val cases = listOf(
            "Lawrence Welk - The Moon Is a Silver Dollar | Joker OST - Original Soundtrack" to
                ("the moon is a silver dollar" to "lawrence welk"),
            "Cream - White Room | Joker OST" to ("white room" to "cream"),
            "Hildur Guðnadóttir, Jóhann Jóhannsson - Bathroom Dance | Joker OST" to
                ("bathroom dance" to "hildur guðnadóttir, jóhann jóhannsson"),
        )
        for ((raw, expected) in cases) {
            val got = identity(raw)
            assertEquals(raw, expected.first, got.title)
            assertEquals(raw, expected.second, got.artist)
            assertEquals(raw, "joker", got.work)
            assertEquals(raw, expected.first.replace(" ", ""), TrackMatcher.parseTitle(raw).core)
        }
    }

    @Test
    fun `a title with only an OST tail asks for the work soundtrack instead of the uploader`() {
        val raw = "Following Sophie | Joker OST"
        val got = identity(raw)
        assertEquals("following sophie", got.title)
        assertEquals("", got.artist)
        assertEquals("joker", got.work)
        assertEquals(
            listOf("following sophie joker soundtrack", "following sophie"),
            TrackMatcher.queries(TrackMatcher.Target(raw, "Joker OST")),
        )
    }

    @Test
    fun `the performer is the query credit when the title names one`() {
        assertEquals(
            listOf("white room cream", "white room"),
            TrackMatcher.queries(TrackMatcher.Target("Cream - White Room | Joker OST", "Joker OST")),
        )
        assertEquals(
            listOf("the moon is a silver dollar lawrence welk", "the moon is a silver dollar"),
            TrackMatcher.queries(
                TrackMatcher.Target(
                    "Lawrence Welk - The Moon Is a Silver Dollar | Joker OST - Original Soundtrack",
                    "Joker OST",
                ),
            ),
        )
        assertEquals(
            listOf("bathroom dance hildur guðnadóttir", "bathroom dance"),
            TrackMatcher.queries(
                TrackMatcher.Target(
                    "Hildur Guðnadóttir, Jóhann Jóhannsson - Bathroom Dance | Joker OST",
                    "Joker OST",
                ),
            ),
        )
    }

    @Test
    fun `From brackets and bare OST labels are packaging`() {
        assertEquals(
            "followingsophie",
            TrackMatcher.parseTitle("Following Sophie (From \"Joker\")").core,
        )
        assertEquals(
            listOf("following sophie joker soundtrack", "following sophie"),
            TrackMatcher.queries(
                TrackMatcher.Target("Following Sophie (From \"Joker\")", "Joker OST"),
            ),
        )
        assertEquals(
            "followingsophie",
            TrackMatcher.parseTitle("Following Sophie OST").core,
        )
        assertEquals(
            "followingsophie",
            TrackMatcher.parseTitle("Following Sophie - Original Soundtrack").core,
        )
    }

    @Test
    fun `the audio switch target carries the performer split from the title`() {
        val song = Song(
            videoId = "joker-white-room",
            title = "Cream - White Room | Joker OST",
            artist = "Joker OST",
            thumbnailUrl = null,
        )
        val target = TrackMatcher.audioSwitchTargetOf(song)
        assertEquals("white room", target.title)
        assertEquals("cream", target.artist)
        assertEquals(
            listOf("white room cream", "white room"),
            TrackMatcher.audioSwitchQueries(song),
        )
    }

    @Test
    fun `a title with no performer matches the catalogue row on its cleaned title`() {
        val song = Song(
            videoId = "joker-following-sophie",
            title = "Following Sophie | Joker OST",
            artist = "Joker OST",
            thumbnailUrl = null,
        )
        val target = TrackMatcher.audioSwitchTargetOf(song)
        assertEquals("following sophie", target.title)
        assertEquals("", target.artist)
        val row = Song(
            videoId = "catalogue-following-sophie",
            title = "Following Sophie",
            artist = "Hildur Guðnadóttir",
            thumbnailUrl = null,
        )
        assertEquals(row, TrackMatcher.bestSameArtist(listOf(row), target))
        assertEquals(row, TrackMatcher.bestOfficialAudioForVideo(listOf(row), target))
    }

    @Test
    fun `a channel label before the title is not a performer`() {
        val raw = "Joker OST - Following Sophie | Joker OST"
        val got = identity(raw)
        assertEquals("following sophie", got.title)
        assertEquals("", got.artist)
        assertEquals("joker", got.work)
        assertEquals(
            listOf("following sophie joker soundtrack", "following sophie"),
            TrackMatcher.queries(TrackMatcher.Target(raw, "Joker OST")),
        )
    }

    @Test
    fun `a take or packaging tail after the soundtrack is not inverted`() {
        val live = TrackMatcher.parseTitle("Song - Live | Joker OST")
        assertEquals("song", live.core)
        assertEquals(setOf("live"), live.versions)

        val remix = TrackMatcher.parseTitle("Song - Remix | Joker OST")
        assertEquals("song", remix.core)
        assertEquals(setOf("remix"), remix.versions)

        val official = TrackMatcher.parseTitle("Song | Official Video | Joker OST")
        assertEquals("song", official.core)
        assertEquals(emptySet<String>(), official.versions)
    }

    @Test
    fun `a take marker after a trailing soundtrack label is kept`() {
        val parts = TrackMatcher.parseTitle("Song - Original Soundtrack (Live)")
        assertEquals("song", parts.core)
        assertEquals(setOf("live"), parts.versions)
        assertEquals(
            listOf("song live artist", "song live"),
            TrackMatcher.queries(
                TrackMatcher.Target("Song - Original Soundtrack (Live)", "Artist"),
            ),
        )
    }

    @Test
    fun `an artist-title with a bracketed take keeps both`() {
        val raw = "Cream - White Room (Live) | Joker OST"
        val got = identity(raw)
        assertEquals("white room (live)", got.title)
        assertEquals("cream", got.artist)
        val parts = TrackMatcher.parseTitle(raw)
        assertEquals("whiteroom", parts.core)
        assertEquals(setOf("live"), parts.versions)
    }

    @Test
    fun `official video tails and cover titles still parse as before`() {
        assertEquals(
            listOf("song artist", "song"),
            TrackMatcher.queries(TrackMatcher.Target("Song (Official Video)", "Artist")),
        )
        assertEquals(
            listOf("song artist", "song"),
            TrackMatcher.queries(TrackMatcher.Target("Song | Official Video", "Artist")),
        )
        assertEquals(
            listOf("paniyon sa atif aslam", "paniyon sa"),
            TrackMatcher.queries(
                TrackMatcher.Target("Paniyon Sa (From \"Satyamev Jayate\")", "Atif Aslam"),
            ),
        )
        assertEquals(
            listOf("song cover"),
            TrackMatcher.queries(TrackMatcher.Target("Song (Cover)", "")),
        )
        assertEquals(
            listOf("여수밤바다 장범준", "여수밤바다", "yeosu night sea"),
            TrackMatcher.queries(TrackMatcher.Target("3.여수밤바다 - Yeosu Night Sea", "장범준")),
        )
    }

    @Test
    fun `a real artist credit is kept when only an OST tail follows the title`() {
        val song = Song(
            videoId = "joker-song",
            title = "Song | Joker OST",
            artist = "Hildur Guðnadóttir",
            thumbnailUrl = null,
        )
        val target = TrackMatcher.audioSwitchTargetOf(song)
        assertEquals("song", target.title)
        assertEquals("Hildur Guðnadóttir", target.artist)
        assertFalse(target.noPerformer)
        val right = Song(
            videoId = "catalogue-song",
            title = "Song",
            artist = "Hildur Guðnadóttir",
            thumbnailUrl = null,
        )
        val wrong = Song(
            videoId = "wrong-song",
            title = "Song",
            artist = "Wrong Artist",
            thumbnailUrl = null,
        )
        assertEquals(right, TrackMatcher.bestSameArtist(listOf(right), target))
        assertNull(TrackMatcher.bestSameArtist(listOf(wrong), target))
    }

    @Test
    fun `a missing artist only means no performer for the soundtrack shape`() {
        val soundtrack = TrackMatcher.audioSwitchTargetOf(
            Song(
                videoId = "joker-following-sophie",
                title = "Following Sophie | Joker OST",
                artist = "Joker OST",
                thumbnailUrl = null,
            ),
        )
        assertTrue(soundtrack.noPerformer)
        val row = Song(
            videoId = "catalogue-following-sophie",
            title = "Following Sophie",
            artist = "Hildur Guðnadóttir",
            thumbnailUrl = null,
        )
        assertEquals(row, TrackMatcher.bestSameArtist(listOf(row), soundtrack))

        val missingArtist = TrackMatcher.Target("Following Sophie", "", isVideo = true)
        assertFalse(missingArtist.noPerformer)
        assertNull(TrackMatcher.bestSameArtist(listOf(row), missingArtist))
        assertNull(TrackMatcher.bestOfficialAudioForVideo(listOf(row), missingArtist))
    }

    @Test
    fun `From work names keep original and motion picture words`() {
        assertEquals(
            "original sin",
            identity("Song (From \"Original Sin\")").work,
        )
        assertEquals(
            "the motion picture",
            identity("Song (From \"The Motion Picture\")").work,
        )
    }

    @Test
    fun `a trailing soundtrack label does not eat the take or packaging after it`() {
        val versioned = TrackMatcher.parseTitle("Song - Original Soundtrack Version")
        assertEquals("song", versioned.core)
        assertEquals(emptySet<String>(), versioned.versions)

        val live = TrackMatcher.parseTitle("Song - Original Soundtrack Live")
        assertEquals("song", live.core)
        assertEquals(setOf("live"), live.versions)

        val midTitle = TrackMatcher.parseTitle("Following Sophie - OST Something")
        assertEquals("followingsophie", midTitle.core)
    }

}
