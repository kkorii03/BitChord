package com.music.bitchord.data.taste

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the ListenBrainz source that can be pinned without a network:
 * the two saved responses, the seed a cover resolves to, matching a search row
 * to a recording, and the cache's expiry rule.
 *
 * The JSON below is trimmed from live token-free responses of
 * `labs.api.listenbrainz.org/recording-search/json` and
 * `labs.api.listenbrainz.org/similar-recordings/json`, keeping the fields the
 * parser reads and a couple of the extra ones it ignores.
 */
class SimilarCandidatesTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private val recordingSearch = """
        [
          {
            "recording_name": "Never Gonna Give You Up",
            "recording_mbid": "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae",
            "release_name": "Whenever You Need Somebody",
            "release_mbid": "bf9e91ea-8029-4a04-a26a-224e00a83266",
            "artist_credit_name": "Rick Astley",
            "artist_credit_id": 32667
          },
          {
            "recording_name": "Never Gonna Give You Up (Rick Astley Cover)",
            "recording_mbid": "cba9a920-0db5-40c8-a20a-1648e8dc58ea",
            "release_name": "Where the Stories Begin",
            "artist_credit_name": "Theragon",
            "artist_credit_id": 2908221
          },
          {
            "recording_name": "Never Gonna Give You Up (Rick Astley cover)",
            "recording_mbid": "8aceaa4f-9790-4d9e-9de4-94aee4cfdd1e",
            "release_name": "Concrete and Gold SS2017",
            "artist_credit_name": "Foo Fighters",
            "artist_credit_id": 176
          }
        ]
    """.trimIndent()

    private val similarRecordings = """
        [
          {
            "recording_mbid": "12f65dca-de8f-43fe-a65d-f12a02aaadf3",
            "recording_name": "Take On Me",
            "artist_credit_name": "a\u2010ha",
            "release_name": "Hunting High and Low",
            "score": 1324,
            "reference_mbid": "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae"
          },
          {
            "recording_mbid": "80033c72-aa19-4ba8-9227-afb075fec46e",
            "recording_name": "Wake Me Up Before You Go-Go",
            "artist_credit_name": "Wham!",
            "release_name": "Make It Big",
            "score": 880,
            "reference_mbid": "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae"
          },
          {
            "recording_mbid": "0e5f1add-c943-49a0-b824-169e7f7b82da",
            "recording_name": "Girls Just Want to Have Fun",
            "artist_credit_name": "Cyndi Lauper",
            "release_name": "She\u2019s So Unusual",
            "score": 777,
            "reference_mbid": "8f3471b5-7e6a-48da-86a9-c1c07a0f47ae"
          }
        ]
    """.trimIndent()

    private fun song(
        id: String,
        title: String,
        artist: String,
        isVideo: Boolean = false,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        isVideo = isVideo,
    )

    @Test
    fun `a recording search answer is read in the order ListenBrainz ranked it`() {
        val hits = requireNotNull(SimilarCandidates.parseRecordingSearch(recordingSearch))

        assertEquals(3, hits.size)
        assertEquals("8f3471b5-7e6a-48da-86a9-c1c07a0f47ae", hits.first().mbid)
        assertEquals("Never Gonna Give You Up", hits.first().title)
        assertEquals("Rick Astley", hits.first().artist)
    }

    @Test
    fun `the seed picks the original, not the first cover that also carries its title`() {
        val hits = requireNotNull(SimilarCandidates.parseRecordingSearch(recordingSearch))
        val seed = SimilarCandidates.Seed("Never Gonna Give You Up", "Rick Astley")

        val picked = SimilarCandidates.pickRecording(hits, seed)

        assertEquals("8f3471b5-7e6a-48da-86a9-c1c07a0f47ae", picked?.mbid)
        // Reordered so the covers come first: artist matching still finds it.
        assertEquals("8f3471b5-7e6a-48da-86a9-c1c07a0f47ae", SimilarCandidates.pickRecording(hits.reversed(), seed)?.mbid)
    }

    @Test
    fun `a seed with no known original artist may still resolve by title alone`() {
        val hits = requireNotNull(SimilarCandidates.parseRecordingSearch(recordingSearch))
        val picked = SimilarCandidates.pickRecording(
            hits,
            SimilarCandidates.Seed("Never Gonna Give You Up", ""),
        )

        assertEquals("8f3471b5-7e6a-48da-86a9-c1c07a0f47ae", picked?.mbid)
    }

    @Test
    fun `a recording whose title does not match is not accepted`() {
        val hits = requireNotNull(SimilarCandidates.parseRecordingSearch(recordingSearch))
        val picked = SimilarCandidates.pickRecording(
            hits,
            SimilarCandidates.Seed("Wonderwall", "Oasis"),
        )

        assertNull(picked)
    }

    @Test
    fun `the similar-recordings answer keeps the title and artist each row already carries`() {
        val similar = SimilarCandidates.parseSimilarRecordings(similarRecordings)

        assertEquals(3, similar?.size ?: -1)
        assertEquals("Take On Me", similar?.first()?.title)
        // U+2010 in the saved response normalises to the same name as "a-ha".
        assertEquals("a\u2010ha", similar?.first()?.artist)
        assertEquals("80033c72-aa19-4ba8-9227-afb075fec46e", similar?.get(1)?.mbid)
    }

    @Test
    fun `an unreadable similar answer is a failure, not an empty success`() {
        assertNull(SimilarCandidates.parseSimilarRecordings("<html>not json</html>"))
        assertNull(SimilarCandidates.parseRecordingSearch("not json"))
    }

    @Test
    fun `the first YouTube row with a matching title and artist wins`() {
        val songs = listOf(
            song("live", "Take On Me (Live)", "Some Other Band"),
            song("cover", "Take On Me (Cover)", "A Tribute Act"),
            song("right", "Take On Me", "A-Ha"),
        )

        val picked = SimilarCandidates.pickYtSong(
            songs,
            SimilarCandidates.SimilarRecording("mbid", "Take On Me", "a-ha"),
        )

        assertEquals("right", picked?.videoId)
    }

    @Test
    fun `a matching title alone is not enough when the artist is different`() {
        val picked = SimilarCandidates.pickYtSong(
            listOf(song("wrong", "Take On Me", "A Tribute Act")),
            SimilarCandidates.SimilarRecording("mbid", "Take On Me", "a-ha"),
        )

        assertNull(picked)
    }

    @Test
    fun `a queue row already shown as a cover seeds its original`() {
        val cover = Song(
            videoId = "cover-video",
            title = "Shape of You",
            artist = "Ed Sheeran · Jane 커버",
            thumbnailUrl = null,
            isVideo = true,
            originalTitle = "Shape of You (Cover by Jane)",
            originalArtist = "Jane",
            coverSinger = "Jane",
            coverOriginalArtist = "Ed Sheeran",
        )

        assertEquals(
            SimilarCandidates.Seed("Shape of You", "Ed Sheeran"),
            SimilarCandidates.seedOf(cover),
        )
    }

    @Test
    fun `punctuation and scripts do not decide whether two names match`() {
        assertTrue(SimilarCandidates.titleMatches("Never Gonna Give You Up (Official Video)", "Never Gonna Give You Up"))
        assertTrue(SimilarCandidates.artistMatches("a\u2010ha", "A-Ha"))
        assertTrue(SimilarCandidates.artistMatches("Rick Astley - Topic", "Rick Astley"))
        assertFalse(SimilarCandidates.titleMatches("Me", "Take On Me"))
        assertFalse(SimilarCandidates.artistMatches("A", "A-ha"))
    }

    @Test
    fun `a seed cache lasts seven days and then expires`() {
        assertTrue(SimilarCandidates.isFresh(now - 6 * day, now))
        assertTrue(SimilarCandidates.isFresh(now - 7 * day + 1, now))
        assertFalse(SimilarCandidates.isFresh(now - 7 * day, now))
        assertFalse(SimilarCandidates.isFresh(0L, now))
    }

    @Test
    fun `similar is fetched at most once per fifteen minutes even as tracks change`() {
        val window = SimilarCandidates.SIMILAR_MIN_INTERVAL_MS

        // Nothing fetched yet.
        assertTrue(SimilarCandidates.shouldFetchSimilar(0L, "", "rickastley", now))
        // Inside the window the answer is reused, whatever is playing.
        assertFalse(SimilarCandidates.shouldFetchSimilar(now, "rickastley", "rickastley", now + 1))
        assertFalse(SimilarCandidates.shouldFetchSimilar(now, "rickastley", "aha", now + window - 1))
        // At the window edge a different artist earns a new fetch...
        assertTrue(SimilarCandidates.shouldFetchSimilar(now, "rickastley", "aha", now + window))
        // ...but the same artist still reuses, and so does an unknown artist.
        assertFalse(SimilarCandidates.shouldFetchSimilar(now, "rickastley", "rickastley", now + window))
        assertFalse(SimilarCandidates.shouldFetchSimilar(now, "rickastley", "", now + window))
        // A previous fetch that never knew its artist learnt nothing, so retry.
        assertTrue(SimilarCandidates.shouldFetchSimilar(now, "", "aha", now + window))
        // A clock that jumps backwards must not bypass the window.
        assertFalse(SimilarCandidates.shouldFetchSimilar(now + 1_000, "aha", "wham", now))
    }

    @Test
    fun `one fetch searches for at most four similar recordings`() {
        assertEquals(4, SimilarCandidates.MAX_SEARCHES_PER_FETCH)
        assertEquals(2, SimilarCandidates.MIN_LAZY_SURVIVORS)
        assertTrue(SimilarCandidates.MAX_SEARCHES_PER_FETCH < SimilarCandidates.MAX_SIMILAR)
    }

    @Test
    fun `a cache key ignores punctuation, case and spacing`() {
        assertEquals(
            SimilarCandidates.cacheKey("Take On Me", "a-ha"),
            SimilarCandidates.cacheKey("take on me", "A\u2010HA"),
        )
        assertEquals("takeonme|", SimilarCandidates.cacheKey("Take On Me", ""))
    }
}
