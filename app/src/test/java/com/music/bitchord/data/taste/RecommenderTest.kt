package com.music.bitchord.data.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RecommenderTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun candidate(
        id: String,
        artist: String,
        baseTitle: String = "song $id",
        singer: String? = null,
        source: CandidateSource = CandidateSource.RADIO_SESSION,
        rank: Int = 0,
    ) = Candidate(
        videoId = id,
        title = baseTitle,
        artistNames = listOf(artist),
        baseTitle = baseTitle,
        source = source,
        sourceRank = rank,
        coverSinger = singer,
    )

    private fun view(
        tracks: Map<String, TrackTaste> = emptyMap(),
        artists: Map<String, ArtistTaste> = emptyMap(),
        singers: Map<String, ArtistTaste> = emptyMap(),
        disliked: Set<String> = emptySet(),
    ) = TasteView(
        tracks = tracks,
        artists = artists,
        coverSingers = singers,
        dislikedArtists = disliked,
    )

    private fun ids(list: List<Candidate>) = list.map { it.videoId }

    @Test
    fun `a disliked track is dropped`() {
        val taste = view(
            tracks = mapOf("bad" to TrackTaste(rating = TasteRating.DISLIKE)),
        )
        val ranked = Recommender.rank(
            listOf(candidate("bad", "A"), candidate("ok", "B")),
            taste,
            SessionContext(),
            now,
            Random(0),
        )
        assertEquals(listOf("ok"), ids(ranked))
    }

    @Test
    fun `a track skipped twice this month is dropped`() {
        val taste = view(
            tracks = mapOf(
                "twice" to TrackTaste(recentSkips = listOf(now - 10 * day, now - 20 * day)),
            ),
        )
        val ranked = Recommender.rank(
            listOf(candidate("twice", "A"), candidate("ok", "B")),
            taste,
            SessionContext(),
            now,
            Random(0),
        )
        assertEquals(listOf("ok"), ids(ranked))
    }

    @Test
    fun `one recent skip and one long ago is not two recent skips`() {
        val taste = view(
            tracks = mapOf(
                "once" to TrackTaste(recentSkips = listOf(now - 10 * day, now - 40 * day)),
            ),
        )
        val ranked = Recommender.rank(
            listOf(candidate("once", "A")),
            taste,
            SessionContext(),
            now,
            Random(0),
        )
        assertEquals(listOf("once"), ids(ranked))
    }

    @Test
    fun `a track by a disliked artist is dropped`() {
        val taste = view(disliked = setOf("bad artist"))
        val ranked = Recommender.rank(
            listOf(candidate("v1", "Bad Artist"), candidate("v2", "Fine Artist")),
            taste,
            SessionContext(),
            now,
            Random(0),
        )
        assertEquals(listOf("v2"), ids(ranked))
    }

    @Test
    fun `a favourite artist ranks above an unknown one`() {
        val taste = view(
            artists = mapOf("favourite star" to ArtistTaste(completes = 5.0, likes = 2.0)),
        )
        val ranked = Recommender.rank(
            listOf(
                candidate("unknown", "Nobody"),
                candidate("favourite", "Favourite Star"),
            ),
            taste,
            SessionContext(),
            now,
            Random(0),
        )
        assertTrue(ids(ranked).indexOf("favourite") < ids(ranked).indexOf("unknown"))
    }

    @Test
    fun `a track played an hour ago is pushed down`() {
        val taste = view(
            artists = mapOf(
                "a" to ArtistTaste(completes = 3.0),
                "b" to ArtistTaste(completes = 3.0),
            ),
            tracks = mapOf("recent" to TrackTaste(lastPlayedAt = now - 60 * 60 * 1000)),
        )
        val ranked = Recommender.rank(
            listOf(candidate("recent", "A"), candidate("other", "B")),
            taste,
            SessionContext(),
            now,
            Random(0),
        )
        assertEquals(listOf("other", "recent"), ids(ranked))
    }

    @Test
    fun `about a quarter of the output is exploration`() {
        val taste = view(
            artists = (1..40).associate { "known $it" to ArtistTaste(completes = 2.0) },
        )
        val candidates = (1..40).map { candidate("known-$it", "Known $it") } +
            (1..40).map { candidate("new-$it", "New $it") }
        val ranked = Recommender.rank(candidates, taste, SessionContext(), now, Random(12_345))
        assertEquals(80, ranked.size)
        // Half the pool is unknown and every candidate survives, so the whole
        // 80 rows are 40 known + 40 new by arithmetic rather than by chance.
        // Epsilon governs the rows emitted while both pools still have tracks,
        // so the quarter has to be measured at the opening of the output.
        val unknown = ids(ranked).take(24).count { it.startsWith("new-") }
        assertTrue("early exploration share was $unknown/24", unknown in 4..8)
        // Explorers are taken by score, so they surface in ranked order.
        assertEquals(
            (1..40).map { "new-$it" }.sorted(),
            ids(ranked).filter { it.startsWith("new-") },
        )
    }

    @Test
    fun `no three tracks by one artist in a row`() {
        val taste = view(artists = mapOf("a" to ArtistTaste(completes = 6.0)))
        val candidates = (1..6).map { candidate("a$it", "A") } +
            (1..4).map { candidate("b$it", "B$it") }
        val ranked = Recommender.rank(candidates, taste, SessionContext(), now, Random(3))
        assertTrue(ranked.size >= 9)
        ranked.windowed(3).forEach { window ->
            assertFalse(
                "three of ${window.map { it.videoId }} by the same artist",
                window.all { it.artistNames.first() == "A" },
            )
        }
    }

    @Test
    fun `two takes of one base title are five positions apart`() {
        val taste = view(
            tracks = mapOf(
                "wide-1" to TrackTaste(completes = 9.0),
                "wide-2" to TrackTaste(completes = 8.0),
            ),
        )
        val candidates = listOf(
            candidate("wide-1", "A", baseTitle = "Wide"),
            candidate("wide-2", "B", baseTitle = "Wide"),
        ) + (1..8).map { candidate("other-$it", "Other $it") }
        val ranked = Recommender.rank(candidates, taste, SessionContext(), now, Random(0))
        val first = ids(ranked).indexOf("wide-1")
        val second = ids(ranked).indexOf("wide-2")
        assertTrue(first >= 0 && second >= 0)
        assertTrue("positions were $first and $second", second - first >= 5)
    }

    @Test
    fun `similar sits between the session radio and a favourite`() {
        val ranked = Recommender.rank(
            listOf(
                candidate("session", "A", source = CandidateSource.RADIO_SESSION),
                candidate("similar", "B", source = CandidateSource.SIMILAR),
                candidate("favourite", "C", source = CandidateSource.FAVOURITE),
            ),
            view(),
            SessionContext(),
            now,
            Random(0),
        )
        assertEquals(listOf("favourite", "similar", "session"), ids(ranked))
    }

    @Test
    fun `the same inputs and seed give the same order`() {
        val taste = view(
            artists = mapOf(
                "a" to ArtistTaste(completes = 2.0),
                "b" to ArtistTaste(completes = 1.0),
            ),
        )
        val candidates = (1..20).map {
            candidate("v$it", "Artist ${it % 4}", baseTitle = "Title $it")
        }
        val once = Recommender.rank(candidates, taste, SessionContext(), now, Random(99))
        val twice = Recommender.rank(candidates, taste, SessionContext(), now, Random(99))
        assertEquals(ids(once), ids(twice))
    }
}
