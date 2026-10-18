package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Edge cases in the gated Home's pure rules: unusable history rows, a zero
 * mix limit, and YouTube's translated titles collapsing onto the built ones.
 */
class YouTubeFallbackFeedRulesTest {

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
    fun `rows without a video id never seed or hide quick picks`() {
        val now = 10_000L
        val plays = listOf(
            play("", now - 1_000),
            play("kept", now - 1_000),
        )

        assertEquals(setOf("kept"), YouTubeFallbackFeed.lastHourIds(plays, now))
        assertEquals("kept", YouTubeFallbackFeed.mostPlayed(plays)?.song?.videoId)
    }

    @Test
    fun `a non-positive mix limit asks for no seeds`() {
        val plays = listOf(play("a", 100))

        assertTrue(YouTubeFallbackFeed.mixSeeds(plays, limit = 0).isEmpty())
        assertTrue(YouTubeFallbackFeed.mixSeeds(plays, limit = -1).isEmpty())
    }

    @Test
    fun `blank artists stay distinct by video id`() {
        val plays = listOf(
            play("a", 100, artist = ""),
            play("b", 200, artist = ""),
            play("c", 300, artist = " "),
        )

        val seeds = YouTubeFallbackFeed.mixSeeds(plays)

        assertEquals(listOf("c", "b", "a"), seeds.map { it.videoId })
        assertEquals(seeds.size, seeds.map { it.videoId }.distinct().size)
    }

    @Test
    fun `translated built titles still lose to the built shelf`() {
        val ordered = YouTubeFallbackFeed.orderedShelves(
            quickPicks = shelf(YouTubeFallbackFeed.QUICK_PICKS_TITLE, "built"),
            listenAgain = shelf("다시 듣기", "recent"),
            myMixes = null,
            liked = null,
            youtubeHome = listOf(
                shelf("빠른 곡", "stale-quick"),
                shelf("다시 듣기", "stale-recent"),
                shelf("YouTube home", "home"),
            ),
            musicChannel = emptyList(),
        )

        assertEquals(
            listOf(
                YouTubeFallbackFeed.QUICK_PICKS_TITLE,
                "다시 듣기",
                "YouTube home",
            ),
            ordered.map { it.title },
        )
        assertEquals(listOf("built"), ordered.first().items.map { it.videoId })
        assertEquals(listOf("recent"), ordered[1].items.map { it.videoId })
    }

    @Test
    fun `a shelf without a title is dropped rather than occupying a slot`() {
        val ordered = YouTubeFallbackFeed.orderedShelves(
            quickPicks = null,
            listenAgain = null,
            myMixes = null,
            liked = null,
            youtubeHome = listOf(shelf("   ", "blank"), shelf("YouTube home", "home")),
            musicChannel = emptyList(),
        )

        assertEquals(listOf("YouTube home"), ordered.map { it.title })
    }
}
