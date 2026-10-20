package com.music.bitchord.data.taste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayOrderTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private val seed = AutoplayOrder.SeedProfile(
        videoId = "hot",
        title = "HOT",
        artistNames = listOf("LE SSERAFIM"),
    )

    private fun candidate(
        id: String,
        source: CandidateSource,
        artist: String = "Artist $id",
        title: String = "Song $id",
        rank: Int = 0,
    ) = Candidate(
        videoId = id,
        title = title,
        artistNames = listOf(artist),
        baseTitle = title,
        source = source,
        sourceRank = rank,
    )

    private fun radio(count: Int) = (1..count).map {
        candidate("radio-$it", CandidateSource.RADIO_SEED, rank = it - 1)
    }

    private fun similar(count: Int) = (1..count).map {
        candidate("sim-$it", CandidateSource.SIMILAR, artist = "Other $it", rank = it - 1)
    }

    @Test
    fun `the seed's radio leads and a favourite is never the next track`() {
        val related = candidate("fav-related", CandidateSource.FAVOURITE, artist = "LE SSERAFIM")
        val unrelated = candidate("fav-ajr", CandidateSource.FAVOURITE, artist = "AJR", title = "Bummerland")
        val ranked = listOf(unrelated, related) + radio(8) + similar(4)

        val out = AutoplayOrder.order(
            candidates = ranked,
            seed = seed,
            seedIds = (radio(8) + similar(4)).mapTo(HashSet()) { it.videoId },
            limit = 18,
        )

        // The ranked list offered the unrelated favourite first; ordering must
        // put the seed's own station in front of it.
        assertTrue(out.take(AutoplayOrder.LEAD_SLOTS).all {
            it.source == CandidateSource.RADIO_SEED || it.source == CandidateSource.SIMILAR
        })
        assertFalse(out.first().videoId == "fav-related")
        assertFalse(out.any { it.videoId == "fav-ajr" })

        val favouriteAt = out.indexOfFirst { it.source == CandidateSource.FAVOURITE }
        assertTrue(favouriteAt >= AutoplayOrder.LEAD_SLOTS)
        for (start in AutoplayOrder.LEAD_SLOTS..out.lastIndex) {
            val window = out.subList(start, minOf(start + AutoplayOrder.FAVOURITE_EVERY, out.size))
            assertTrue(window.count { it.source == CandidateSource.FAVOURITE } <= 1)
        }
    }

    @Test
    fun `a favourite already played or queued this session is not re-added`() {
        val fav = candidate("fav-session", CandidateSource.FAVOURITE, artist = "LE SSERAFIM")
        val out = AutoplayOrder.order(
            candidates = listOf(fav) + radio(8),
            seed = seed,
            seedIds = radio(8).mapTo(HashSet()) { it.videoId },
            excludedIds = setOf("fav-session", "radio-1"),
            limit = 10,
        )

        assertFalse(out.any { it.videoId == "fav-session" })
        assertFalse(out.any { it.videoId == "radio-1" })
    }

    @Test
    fun `a local row with no title or artist is never a candidate`() {
        val blank = LocalTrack(videoId = "blank", title = "", artist = "")
        val pool = TastePool.build(
            PoolSources(local = listOf(blank)),
            TasteView(tracks = mapOf("blank" to TrackTaste(completes = 9.0))),
            now,
        )

        assertTrue(pool.candidates.isEmpty())
    }

    @Test
    fun `a favourite suggested today is held back for a day`() {
        fun local(id: String) = LocalTrack(videoId = id, title = "Song $id", artist = "Artist $id")

        val taste = TasteView(
            tracks = mapOf(
                "suggested" to TrackTaste(completes = 9.0, lastSuggestedAt = now - 60 * 60 * 1000),
                "ready" to TrackTaste(completes = 9.0, lastSuggestedAt = now - day - 1000),
            ),
        )
        val pool = TastePool.build(
            PoolSources(local = listOf(local("suggested"), local("ready"))),
            taste,
            now,
        )

        assertEquals(listOf("ready"), pool.candidates.map { it.videoId })
    }
}
