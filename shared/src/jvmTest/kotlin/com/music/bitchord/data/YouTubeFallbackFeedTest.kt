package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gated Home's pure rules: which recent song seeds Quick picks, which rows
 * the last hour hides, how many different artists seed the mixes, and the
 * shelf order the listener sees after the individual fetches land.
 */
class YouTubeFallbackFeedTest {

    private fun song(id: String, artist: String = "Artist $id", title: String = "Song $id") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    private fun play(id: String, at: Long, artist: String = "Artist $id") =
        LocalPlay(song(id, artist = artist), at)

    private fun shelf(title: String, vararg ids: String): HomeShelf = HomeShelf(
        title = title,
        items = ids.map {
            ShelfItem(
                title = "Song $it",
                subtitle = "Artist",
                thumbnailUrl = null,
                videoId = it,
                browseId = null,
            )
        },
    )

    @Test
    fun `quick picks excludes only the last hour`() {
        val now = 10 * YouTubeFallbackFeed.LAST_HOUR_MS
        val plays = listOf(
            play("recent", now - 1_000),
            play("edge", now - YouTubeFallbackFeed.LAST_HOUR_MS),
            play("older", now - YouTubeFallbackFeed.LAST_HOUR_MS - 1),
        )

        assertEquals(
            setOf("recent"),
            YouTubeFallbackFeed.lastHourIds(plays, now),
        )
    }

    @Test
    fun `the most played song seeds quick picks and recency breaks a tie`() {
        val plays = listOf(
            play("a", 100),
            play("b", 500),
            play("a", 400),
            play("c", 600),
        )

        assertEquals("a", YouTubeFallbackFeed.mostPlayed(plays)?.song?.videoId)
        assertEquals("c", YouTubeFallbackFeed.mostPlayed(plays.drop(2))?.song?.videoId)
    }

    @Test
    fun `mixes come from different artists before repeating one`() {
        val plays = listOf(
            play("a1", 100, artist = "A"),
            play("a2", 200, artist = "A"),
            play("b1", 300, artist = "B"),
            play("b2", 400, artist = "B"),
            play("c1", 500, artist = "C"),
            play("d1", 600, artist = "D"),
            play("e1", 700, artist = "E"),
            play("f1", 800, artist = "F"),
            play("g1", 900, artist = "G"),
        )

        val seeds = YouTubeFallbackFeed.mixSeeds(plays)

        assertEquals(YouTubeFallbackFeed.MIX_LIMIT, seeds.size)
        // Six distinct artists, even though A and B were played twice each and
        // so lead the artist ranking.
        assertEquals(seeds.size, seeds.map { it.artist }.distinct().size)
        assertEquals(listOf("B", "A", "G", "F", "E", "D"), seeds.map { it.artist })
        assertEquals("b2", seeds.first().videoId)
    }

    @Test
    fun `mixes fill a short artist list with the newest remaining songs`() {
        val plays = listOf(
            play("a1", 100, artist = "A"),
            play("a2", 200, artist = "A"),
            play("a3", 300, artist = "A"),
            play("b1", 400, artist = "B"),
        )

        val seeds = YouTubeFallbackFeed.mixSeeds(plays)

        assertEquals(listOf("a3", "b1", "a2", "a1"), seeds.map { it.videoId })
        assertTrue(seeds.all { it.videoId.isNotBlank() })
    }

    @Test
    fun `shelves lead with Recents then Listen again then Quick picks and drop repeats`() {
        val ordered = YouTubeFallbackFeed.orderedShelves(
            recents = shelf(YouTubeFallbackFeed.RECENT_TITLE, "r"),
            listenAgain = shelf(YouTubeFallbackFeed.LISTEN_AGAIN_TITLE, "l"),
            quickPicks = shelf(YouTubeFallbackFeed.QUICK_PICKS_TITLE, "q"),
            myMixes = shelf(YouTubeFallbackFeed.MY_MIXES_TITLE, "m"),
            liked = shelf(YouTubeFallbackFeed.LIKED_TITLE, "k"),
            youtubeHome = listOf(
                shelf(YouTubeFallbackFeed.QUICK_PICKS_TITLE, "stale"),
                shelf("YouTube home", "y"),
            ),
            musicChannel = listOf(shelf("Music channel", "c")),
        )

        assertEquals(
            listOf(
                YouTubeFallbackFeed.RECENT_TITLE,
                YouTubeFallbackFeed.LISTEN_AGAIN_TITLE,
                YouTubeFallbackFeed.QUICK_PICKS_TITLE,
                YouTubeFallbackFeed.MY_MIXES_TITLE,
                YouTubeFallbackFeed.LIKED_TITLE,
                "YouTube home",
                "Music channel",
            ),
            ordered.map { it.title },
        )
        // Recents keeps the leading slot the UI's toggle rides on.
        assertEquals(listOf("r"), ordered.first().items.map { it.videoId })
        // The stale YouTube copy lost to the built Quick picks.
        assertEquals(listOf("q"), ordered[2].items.map { it.videoId })
    }

    @Test
    fun `empty shelves are left out`() {
        val ordered = YouTubeFallbackFeed.orderedShelves(
            recents = null,
            listenAgain = null,
            quickPicks = HomeShelf(YouTubeFallbackFeed.QUICK_PICKS_TITLE, emptyList()),
            myMixes = null,
            liked = null,
            youtubeHome = emptyList(),
            musicChannel = listOf(shelf("Music channel", "c")),
        )

        assertEquals(listOf("Music channel"), ordered.map { it.title })
    }

    @Test
    fun `a song id is shown by at most one shelf in priority order`() {
        val deduped = YouTubeFallbackFeed.dedupeShelves(
            listOf(
                shelf(YouTubeFallbackFeed.RECENT_TITLE, "shared", "recent"),
                shelf(YouTubeFallbackFeed.LISTEN_AGAIN_TITLE, "shared"),
                shelf(YouTubeFallbackFeed.QUICK_PICKS_TITLE, "shared", "quick"),
                shelf(YouTubeFallbackFeed.MY_MIXES_TITLE, "quick", "mix"),
                shelf(YouTubeFallbackFeed.LIKED_TITLE, "mix", "liked"),
                shelf("YouTube home", "liked", "home"),
            ),
        )

        assertEquals(
            mapOf(
                YouTubeFallbackFeed.RECENT_TITLE to listOf("shared", "recent"),
                YouTubeFallbackFeed.QUICK_PICKS_TITLE to listOf("quick"),
                YouTubeFallbackFeed.MY_MIXES_TITLE to listOf("mix"),
                YouTubeFallbackFeed.LIKED_TITLE to listOf("liked"),
                "YouTube home" to listOf("home"),
            ),
            deduped.associate { it.title to it.items.map { item -> item.videoId } },
        )
    }

    @Test
    fun `rediscoveries are the most played rows whose last play is old enough`() {
        val now = 100L * 24L * 60L * 60L * 1000L
        val day = 24L * 60L * 60L * 1000L
        val candidates = listOf(
            play("just-played", now - day).copy(plays = 50),
            play("old-favourite", now - 30 * day).copy(plays = 20),
            play("older-favourite", now - 40 * day).copy(plays = 20),
            play("rare", now - 60 * day).copy(plays = 2),
        )

        val picked = HomeFallbackRules.rediscover(
            candidates = candidates,
            nowMs = now,
            limit = 2,
        )

        // Highest counts first for selection, oldest last play first in the row.
        assertEquals(listOf("older-favourite", "old-favourite"), picked.map { it.song.videoId })
    }

    @Test
    fun `rediscoveries ignore the last fourteen days and blank ids`() {
        val now = 100L * 24L * 60L * 60L * 1000L
        val day = 24L * 60L * 60L * 1000L
        val candidates = listOf(
            play("", now - 30 * day).copy(plays = 99),
            play("thirteen-days", now - 13 * day).copy(plays = 50),
            play("fourteen-days", now - 14 * day).copy(plays = 50),
            play("fifteen-days", now - 15 * day).copy(plays = 50),
        )

        val picked = HomeFallbackRules.rediscover(candidates, nowMs = now, limit = 10)

        // Exactly fourteen days is not yet "older than" fourteen days.
        assertEquals(listOf("fifteen-days"), picked.map { it.song.videoId })
    }

    @Test
    fun `interleaving leads with the rediscoveries and takes each list in turn`() {
        val mixed = HomeFallbackRules.interleave(
            first = listOf("r1", "r2", "r3"),
            second = listOf("m1", "m2"),
            limit = 10,
        )

        assertEquals(listOf("r1", "m1", "r2", "m2", "r3"), mixed)
        assertEquals(
            listOf("r1", "m1", "r2"),
            HomeFallbackRules.interleave(listOf("r1", "r2"), listOf("m1", "m2"), limit = 3),
        )
    }
}
