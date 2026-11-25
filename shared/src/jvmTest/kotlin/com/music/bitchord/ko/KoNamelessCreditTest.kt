package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Phone 11-24/25: a song played as a violin. JioSaavn files its instrumental
 * under the song's own title and length, credited to Various Artists; the same
 * backing passed the sound check at 100%, the words hearing (9 sung on YouTube,
 * none on the violin, 54 s) answered "undecided" and was stored as settled, and
 * the library fetched it again after the user deleted it. Its "320 kbps" file
 * was 98 kbps. Old code admitted the row, kept "undecided" and saved the file.
 */
class KoNamelessCreditTest {

    @get:Rule
    val koTimeout: Timeout = Timeout.seconds(60)

    private val request = TrackMatcher.Target("눈의 꽃", "박효신", durationSec = 340)

    private fun row(artist: String, album: String?, title: String = "눈의 꽃") = Song(
        videoId = "row", title = title, artist = artist, thumbnailUrl = null, albumName = album, durationText = "5:40",
    )

    private fun verdict(candidate: Song, target: TrackMatcher.Target = request) = TrackMatcher.rejectionReason(
        candidate,
        target,
        strictOriginal = true,
        requireSharedArtist = true,
        strictLength = true,
        driftSec = 3,
        allowArtistUnverified = true,
    )

    @Test
    fun `a nameless row on an instrument release is another version`() {
        listOf(
            "최신 영화 드라마 OST 피아노 With 바이올린 베스트",
            "바이올린 연주로 듣는 최신드라마 OST 베스트",
            "최신 드라마 OST 가요발라드(CD 3. 아름다운 바이올린)",
            "세상에서 가장 편안한 자장가 첼로&피아노",
            "JAZZ LOUNGE CAFE - Piano Instrument",
        ).forEach { assertEquals(it, "version", verdict(row("Various Artists", it))) }
    }

    @Test
    fun `an ordinary compilation, the song's own words and a real credit are untouched`() {
        assertEquals("artist-unverified", verdict(row("Various Artists", "미안하다 사랑한다 OST")))
        assertEquals("artist-unverified", verdict(row("Various Artists", null)))
        val own = TrackMatcher.Target("피아노", "박효신", durationSec = 340)
        assertEquals("artist-unverified", verdict(row("Various Artists", "피아노", title = "피아노"), own))
        assertEquals(null, verdict(row("박효신", "피아노 앨범")))
    }

    @Test
    fun `only a Various label names nobody`() {
        assertTrue(KoVersionGuard.namesNobody("Various Artists"))
        assertTrue(KoVersionGuard.namesNobody("여러 아티스트"))
        assertFalse(KoVersionGuard.namesNobody("Original Soundtrack"))
        assertFalse(KoVersionGuard.namesNobody("박효신"))
        assertFalse(KoVersionGuard.namesNobody(null))
    }

    private fun heard(count: Int) = List(count) { LyricsWordAlign.Heard("word$it", 3_000L + it * 5_000L, 3_400L + it * 5_000L) }

    private fun check(reference: Int, candidate: Int, untilMs: Long = 54_000L) = KoVersionGuard.compareWords(
        reference = heard(reference),
        referenceUntilMs = untilMs,
        candidate = heard(candidate),
        candidateUntilMs = untilMs,
        offsetMs = 0L,
        rate = 1.0,
    )

    @Test
    fun `a copy with no words where the song sings is another recording`() {
        val violin = check(9, 0)
        assertEquals(KoVersionGuard.Words.UNDECIDED, violin.verdict)
        val judged = KoVersionGuard.judged(violin)
        assertEquals(KoVersionGuard.Words.DIFFERENT, judged.verdict)
        assertEquals(
            KoVersionGuard.Decision.REJECT,
            KoVersionGuard.decide(chromaAccepted = true, needsWords = true, words = judged),
        )
        assertEquals(KoVersionGuard.CachedCopy.REFUSE, KoVersionGuard.cachedCopyVerdict(judged))
    }

    @Test
    fun `too little to hear stays undecided`() {
        // A quiet opening, a short window, an instrumental song.
        listOf(check(4, 0), check(9, 0, untilMs = 20_000L), check(0, 0)).forEach {
            assertEquals(it, KoVersionGuard.judged(it))
        }
        val same = check(9, 9)
        assertEquals(KoVersionGuard.Words.SAME, same.verdict)
        assertEquals(same, KoVersionGuard.judged(same))
    }

    @Test
    fun `a nameless row plays only on the song's own words`() {
        assertTrue(KoVersionGuard.namelessStands(check(9, 9)))
        assertFalse(KoVersionGuard.namelessStands(check(4, 0)))
        assertFalse(KoVersionGuard.namelessStands(null))
    }

    @Test
    fun `a file far smaller than its bitrate is thin`() {
        val claimed = StreamFormat(codec = "aac", kbps = 320)
        assertTrue(KoThinRendition.thin(claimed, 340, 4_154_209L))
        assertFalse(KoThinRendition.thin(claimed, 340, 340L * 320_000 / 8))
        assertFalse(KoThinRendition.thin(claimed, 340, 0L))
        assertFalse(KoThinRendition.thin(claimed, null, 4_154_209L))
        assertFalse(KoThinRendition.thin(StreamFormat(codec = "aac", kbps = 96), 340, 4_154_209L))
        assertFalse(KoThinRendition.thin(StreamFormat(codec = "flac", kbps = 900), 340, 4_154_209L))
    }
}