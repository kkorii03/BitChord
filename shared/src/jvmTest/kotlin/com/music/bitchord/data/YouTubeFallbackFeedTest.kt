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
    fun `shelves keep the Music Home order and drop repeats`() {
        val ordered = YouTubeFallbackFeed.orderedShelves(
            quickPicks = shelf(YouTubeFallbackFeed.QUICK_PICKS_TITLE, "q"),
            listenAgain = shelf(YouTubeFallbackFeed.RECENT_TITLE, "l"),
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
                YouTubeFallbackFeed.QUICK_PICKS_TITLE,
                YouTubeFallbackFeed.RECENT_TITLE,
                YouTubeFallbackFeed.MY_MIXES_TITLE,
                YouTubeFallbackFeed.LIKED_TITLE,
                "YouTube home",
                "Music channel",
            ),
            ordered.map { it.title },
        )
        // The stale YouTube copy lost to the built Quick picks.
        assertEquals(listOf("q"), ordered.first().items.map { it.videoId })
    }

    @Test
    fun `empty shelves are left out`() {
        val ordered = YouTubeFallbackFeed.orderedShelves(
            quickPicks = HomeShelf(YouTubeFallbackFeed.QUICK_PICKS_TITLE, emptyList()),
            listenAgain = null,
            myMixes = null,
            liked = null,
            youtubeHome = emptyList(),
            musicChannel = listOf(shelf("Music channel", "c")),
        )

        assertEquals(listOf("Music channel"), ordered.map { it.title })
    }
}
