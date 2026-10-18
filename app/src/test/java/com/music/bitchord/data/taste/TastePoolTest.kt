package com.music.bitchord.data.taste

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TastePoolTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun song(
        id: String,
        artist: String = "Artist $id",
        title: String = "Song $id",
        isVideo: Boolean = false,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        isVideo = isVideo,
    )

    private fun local(
        id: String,
        artist: String = "Artist $id",
        title: String = "Local $id",
        singer: String? = null,
    ) = LocalTrack(videoId = id, title = title, artist = artist, coverSinger = singer)

    private fun view(tracks: Map<String, TrackTaste> = emptyMap()) = TasteView(tracks = tracks)

    private fun ids(pool: CandidatePool) = pool.candidates.map { it.videoId }

    private fun listened(completes: Double = 1.0, plays: Double = 1.0, last: Long = 0L) =
        TrackTaste(plays = plays, completes = completes, lastPlayedAt = last)

    @Test
    fun `the seed radio is kept whole and favourites are capped at six`() {
        val radio = (1..12).map { song("radio-$it") }
        val library = (1..8).map { local("fav-$it") }
        val taste = view(local.associate { it.videoId to listened(completes = 5.0) })

        val pool = TastePool.build(
            PoolSources(seedRadio = radio, local = library),
            taste,
            now,
        )

        assertEquals(12, pool.counts[CandidateSource.RADIO_SEED])
        assertEquals(TastePool.FAVOURITE_LIMIT, pool.counts[CandidateSource.FAVOURITE])
        assertEquals(0, pool.counts[CandidateSource.REDISCOVER])
        assertEquals(18, pool.candidates.size)
        assertTrue(ids(pool).take(12).all { it.startsWith("radio-") })
    }

    @Test
    fun `the session radio adds its own source after the seed`() {
        val pool = TastePool.build(
            PoolSources(
                seedRadio = listOf(song("seed-1")),
                sessionRadio = listOf(song("session-1"), song("session-2")),
            ),
            view(),
            now,
        )

        assertEquals(listOf("seed-1", "session-1", "session-2"), ids(pool))
        assertEquals(2, pool.counts[CandidateSource.RADIO_SESSION])
        assertEquals(CandidateSource.RADIO_SESSION, pool.candidates[1].source)
        assertEquals(1, pool.candidates[1].sourceRank)
    }

    @Test
    fun `a track played today is not offered as a favourite`() {
        val library = listOf(local("today"), local("yesterday"), local("never"))
        val taste = view(
            mapOf(
                "today" to listened(completes = 9.0, last = now - 60 * 60 * 1000),
                "yesterday" to listened(completes = 9.0, last = now - 2 * day),
            ),
        )

        val pool = TastePool.build(PoolSources(local = library), taste, now)

        val favourites = ids(pool)
        assertFalse("today" in favourites)
        assertTrue("yesterday" in favourites)
        assertTrue("never" in favourites)
    }

    @Test
    fun `favourites are ordered by affinity and skips lower a track`() {
        val library = listOf(local("loved"), local("skipped"))
        val taste = view(
            mapOf(
                "loved" to TrackTaste(completes = 6.0, likes = 3.0),
                "skipped" to TrackTaste(completes = 1.0, skips = 8.0),
            ),
        )

        val pool = TastePool.build(PoolSources(local = library), taste, now)

        assertEquals(listOf("loved", "skipped"), ids(pool))
    }

    @Test
    fun `liked but rarely played tracks become rediscover, capped at three`() {
        // Six well-played favourites fill the favourite cap, so the liked rows
        // with no plays are what is left for rediscover.
        val favourites = (1..6).map { local("played-$it") }
        val rare = (1..3).map { local("liked-$it") }
        val taste = view(
            favourites.associate { it.videoId to TrackTaste(plays = 8.0, completes = 6.0) } +
                rare.associate { it.videoId to TrackTaste(plays = 0.0, rating = TasteRating.LIKE) },
        )

        val pool = TastePool.build(PoolSources(local = favourites + rare), taste, now)

        assertEquals(TastePool.FAVOURITE_LIMIT, pool.counts[CandidateSource.FAVOURITE])
        assertEquals(TastePool.REDISCOVER_LIMIT, pool.counts[CandidateSource.REDISCOVER])
        assertEquals(listOf("liked-1", "liked-2", "liked-3"), ids(pool).takeLast(3))
    }

    @Test
    fun `an unliked track is never rediscover`() {
        val library = listOf(local("plain"))
        val taste = view(mapOf("plain" to TrackTaste(plays = 0.0, rating = TasteRating.NONE)))

        val pool = TastePool.build(PoolSources(local = library), taste, now)

        assertEquals(0, pool.counts[CandidateSource.REDISCOVER])
    }

    @Test
    fun `a duplicate keeps the stronger source`() {
        val duplicate = local("dup")
        val taste = view(mapOf("dup" to TrackTaste(plays = 0.0, rating = TasteRating.LIKE)))

        val pool = TastePool.build(
            PoolSources(seedRadio = listOf(song("dup")), local = listOf(duplicate)),
            taste,
            now,
        )

        assertEquals(listOf("dup"), ids(pool))
        assertEquals(CandidateSource.RADIO_SEED, pool.candidates.single().source)
    }

    @Test
    fun `base title and cover singer are read the way the queue reads them`() {
        val radio = listOf(
            song("plain", title = "Kesariya (Official Video)"),
            song("cover", artist = "Jane", title = "Shape of You (Cover by Jane)"),
        )

        val pool = TastePool.build(PoolSources(seedRadio = radio), view(), now)
        val byId = pool.candidates.associateBy { it.videoId }

        assertEquals("kesariya", byId.getValue("plain").baseTitle)
        assertNull(byId.getValue("plain").coverSinger)
        assertEquals("shape of you", byId.getValue("cover").baseTitle)
        assertEquals("Jane", byId.getValue("cover").coverSinger)
    }

    @Test
    fun `a local row is rebuilt as a playable song`() {
        val plain = local("plain").asSong()
        val cover = local("cover", singer = "Jane").asSong()

        assertEquals("plain", plain.videoId)
        assertFalse(plain.isVideo)
        // QueueBuilder drops video rows when a batch has catalogue tracks, so
        // a stored cover must not be marked as one.
        assertFalse(cover.isVideo)
        assertEquals("Jane", local("cover", singer = "Jane").coverSinger)
    }
}
