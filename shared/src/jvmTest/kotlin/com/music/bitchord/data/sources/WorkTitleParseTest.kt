package com.music.bitchord.data.sources

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The audio switch's reading of an upload title.
 *
 * A soundtrack track uploaded by a fan is titled with the song, the credit
 * the uploader gives it and the work it comes from — "Cream - White Room |
 * Joker OST". A catalogue files the track under the song and the credit, so
 * the work is packaging and the uploader's channel is nobody who recorded
 * anything. These cases pin where the song ends, where the credit ends and
 * what the switch asks for.
 */
class WorkTitleParseTest {

    private fun song(
        id: String,
        title: String,
        artist: String,
        duration: String? = null,
        isVideo: Boolean = false,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        isVideo = isVideo,
    )

    @Test
    fun `a soundtrack upload is split into its song, its credit and its work`() {
        val upload = TrackMatcher.parseWorkTitle("Cream - White Room | Joker OST")
        assertNotNull(upload)
        assertEquals("White Room", upload.title)
        assertEquals("Cream", upload.artist)
        assertEquals("Joker", upload.work)
        assertTrue(upload.statesArtist)
    }

    @Test
    fun `the label at the end of the work tail is packaging too`() {
        val upload = TrackMatcher.parseWorkTitle(
            "Lawrence Welk - The Moon Is a Silver Dollar | Joker OST - Original Soundtrack",
        )
        assertNotNull(upload)
        assertEquals("The Moon Is a Silver Dollar", upload.title)
        assertEquals("Lawrence Welk", upload.artist)
        assertEquals("Joker", upload.work)
    }

    @Test
    fun `a title that names no credit states none`() {
        val upload = TrackMatcher.parseWorkTitle("Following Sophie | Joker OST")
        assertNotNull(upload)
        assertEquals("Following Sophie", upload.title)
        assertEquals("", upload.artist)
        assertEquals("Joker", upload.work)
        assertFalse(upload.statesArtist)
    }

    @Test
    fun `a credit of several names is kept whole`() {
        val upload = TrackMatcher.parseWorkTitle(
            "Hildur Guðnadóttir, Jóhann Jóhannsson - Following Sophie | Joker OST",
        )
        assertNotNull(upload)
        assertEquals("Following Sophie", upload.title)
        assertEquals("Hildur Guðnadóttir, Jóhann Jóhannsson", upload.artist)
        assertEquals("Joker", upload.work)
    }

    @Test
    fun `a work stated in brackets is packaging too`() {
        val upload = TrackMatcher.parseWorkTitle("Paniyon Sa (From \"Satyamev Jayate\")")
        assertNotNull(upload)
        assertEquals("Paniyon Sa", upload.title)
        assertEquals("", upload.artist)
        assertEquals("Satyamev Jayate", upload.work)
        // A plain film name is a catalogue title's own shape: the credit
        // there is the artist, not an uploader's channel.
        assertFalse(upload.ostTail)
    }

    @Test
    fun `a catalogue title with a work in brackets is asked for as it always was`() {
        val row = song(
            "CATALOGUEROW",
            "Paniyon Sa (From \"Satyamev Jayate\")",
            "Atif Aslam",
            "3:29",
        )
        val plan = TrackMatcher.audioSwitchPlan(row)
        assertEquals("Paniyon Sa", plan.target.title)
        assertEquals("Atif Aslam", plan.target.artist)
        assertEquals(listOf("paniyon sa atif aslam", "paniyon sa"), plan.queries)
    }

    @Test
    fun `a work named with the OST label is a soundtrack tail`() {
        val upload = TrackMatcher.parseWorkTitle("Following Sophie (From Joker OST)")
        assertNotNull(upload)
        assertEquals("Following Sophie", upload.title)
        assertEquals("Joker", upload.work)
        assertTrue(upload.ostTail)
    }

    @Test
    fun `a bare OST label is packaging without naming a work`() {
        val upload = TrackMatcher.parseWorkTitle("Following Sophie OST")
        assertNotNull(upload)
        assertEquals("Following Sophie", upload.title)
        assertEquals("", upload.artist)
        assertEquals("", upload.work)
    }

    @Test
    fun `a title with no work tail is none of this parser's business`() {
        listOf(
            "IU - Friday (Official Music Video)",
            "[MV] 아이유 - 금요일에 만나요 (Feat. 장이정)",
            "Peaches (Originally performed by Justin Bieber)",
            "After LIKE (Official MV)",
            "여수밤바다 - Yeosu Night Sea",
            "Paniyon Sa - Satyamev Jayate",
            "Born This Way",
            "3. Ditto",
        ).forEach { raw ->
            assertNull(TrackMatcher.parseWorkTitle(raw), raw)
        }
    }

    @Test
    fun `a dash inside the song's own name is not a credit`() {
        val upload = TrackMatcher.parseWorkTitle(
            "The Moon Is a Silver Dollar - Instrumental | Joker OST",
        )
        assertNotNull(upload)
        assertEquals("The Moon Is a Silver Dollar - Instrumental", upload.title)
        assertEquals("", upload.artist)
    }

    @Test
    fun `a tail that names a take is not a credit either`() {
        val upload = TrackMatcher.parseWorkTitle("Song - Live | Joker OST")
        assertNotNull(upload)
        assertEquals("Song - Live", upload.title)
        assertEquals("", upload.artist)

        val edition = TrackMatcher.parseWorkTitle("White Room - Special Edition | Joker OST")
        assertNotNull(edition)
        assertEquals("White Room - Special Edition", edition.title)
        assertEquals("", edition.artist)
    }

    @Test
    fun `the credit the title states is what the switch asks for`() {
        val upload = TrackMatcher.parseWorkTitle("Cream - White Room | Joker OST")
        assertNotNull(upload)
        val target = TrackMatcher.Target(
            title = upload.title,
            artist = upload.artist,
            durationSec = 259,
            isVideo = true,
        )
        assertEquals(
            listOf("white room cream", "white room"),
            TrackMatcher.audioSwitchQueries(target, upload),
        )
    }

    @Test
    fun `a title with no credit is asked for by the work, not by the uploader`() {
        val upload = TrackMatcher.parseWorkTitle("Following Sophie | Joker OST")
        assertNotNull(upload)
        val target = TrackMatcher.Target(
            title = upload.title,
            artist = "Joker OST Playlist",
            durationSec = 180,
            isVideo = true,
        )
        val queries = TrackMatcher.audioSwitchQueries(target, upload)
        assertEquals(
            listOf("following sophie joker soundtrack", "following sophie"),
            queries,
        )
        assertTrue(queries.none { it.contains("playlist") })
    }

    @Test
    fun `the whole plan for a soundtrack upload asks for the song and the credit`() {
        val upload = song(
            "UPLOAD00001",
            "Cream - White Room | Joker OST",
            "Joker OST Playlist",
            "4:19",
            isVideo = true,
        )
        val plan = TrackMatcher.audioSwitchPlan(upload)
        assertEquals("White Room", plan.target.title)
        assertEquals("Cream", plan.target.artist)
        assertTrue(plan.target.isVideo)
        assertEquals(listOf("white room cream", "white room"), plan.queries)
    }

    @Test
    fun `the plan for an ordinary title is the one it always was`() {
        val ordinary = song(
            "ORDINARY001",
            "After LIKE (Official Music Video)",
            "IVE",
            "3:12",
            isVideo = true,
        )
        val plan = TrackMatcher.audioSwitchPlan(ordinary)
        assertEquals("After LIKE (Official Music Video)", plan.target.title)
        assertEquals("IVE", plan.target.artist)
        assertEquals(TrackMatcher.queries(plan.target), plan.queries)
    }

    @Test
    fun `a soundtrack upload finds the catalogue song its title names`() {
        val upload = song(
            "UPLOAD00002",
            "Cream - White Room | Joker OST",
            "Joker OST Playlist",
            "4:19",
            isVideo = true,
        )
        val plan = TrackMatcher.audioSwitchPlan(upload)
        val catalogue = song("CATALOGUE01", "White Room", "Cream", "4:13")
        assertEquals(
            "CATALOGUE01",
            TrackMatcher.bestSameArtist(listOf(catalogue), plan.target)?.videoId,
        )
    }

    @Test
    fun `the raw upload title is no longer the identity it was read as`() {
        val upload = song(
            "UPLOAD00003",
            "Cream - White Room | Joker OST",
            "Joker OST Playlist",
            "4:19",
            isVideo = true,
        )
        // Before the work tail was read, the title was the uploader's head —
        // "Cream" — and the catalogue row could never be the same recording.
        val raw = TrackMatcher.targetOf(upload)
        assertEquals("cream", TrackMatcher.parseTitle(raw.title).core)
        val plan = TrackMatcher.audioSwitchPlan(upload)
        assertEquals("whiteroom", TrackMatcher.parseTitle(plan.target.title).core)
    }

    @Test
    fun `the audio switch writes the query it asked for`() {
        // The search request logs only the length of a query, so this line is
        // the only place a device log says what was asked for.
        assertEquals(
            "audio switch: query=white room cream",
            YtMusicRepository.audioSwitchQueryLine("white room cream"),
        )
    }
}
