package com.music.bitchord.ko.ui

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.contextEntryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * shuf500: the 554-row playlist was shuffled as 618 rows. The page still
 * showed its downloaded copy (64 rows, no setVideoId) when the service's
 * registry was mirrored into it, and no downloaded key matched a registry
 * `videoId@setVideoId` key, so each downloaded song was queued twice.
 */
class KoDownloadedPreviewQueueShuffleTest {

    @get:Rule
    val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

    @Test
    fun `the registry replaces a downloaded preview instead of joining it`() {
        val registry = List(554) { row(it, set = "set-$it") }
        val downloaded = List(64) { row(it * 3, set = null) }

        val rows = requireNotNull(koMirroredDetailRows(downloaded, preview = true, mirrored = registry))

        assertEquals(554, rows.size)
        assertEquals(rows.size, rows.map { it.videoId }.toSet().size)
    }

    @Test
    fun `a song the playlist lists twice stays twice`() {
        val page = listOf(row(1, set = "a"), row(2, set = "b"))
        val registry = page + row(1, set = "c") + row(3, set = "d")

        val rows = requireNotNull(koMirroredDetailRows(page, preview = false, mirrored = registry))

        assertEquals(listOf("v1@a", "v2@b", "v1@c", "v3@d"), rows.map { it.contextEntryKey() })
    }

    @Test
    fun `nothing new and no rows to mirror leave the page alone`() {
        val page = listOf(row(1, set = "a"))
        assertNull(koMirroredDetailRows(page, preview = false, mirrored = page))
        // An empty registry never wipes the downloaded copy.
        assertNull(koMirroredDetailRows(page, preview = true, mirrored = emptyList()))
    }

    private fun row(index: Int, set: String?) = Song(
        videoId = "v$index",
        title = "Title $index",
        artist = "Artist",
        thumbnailUrl = "https://img/$index",
        durationText = "3:00",
    ).copy(setVideoId = set)
}
