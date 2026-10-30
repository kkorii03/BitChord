package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
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
}
