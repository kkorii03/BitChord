package com.music.bitchord.data.cover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A list/shelf mapping learns every singer it reads in one hand-over, so the
 * app writes its cover-singer set once per burst instead of once per name.
 */
class CoverTitlesBatchTest {

    private val singles = mutableListOf<String>()
    private val batches = mutableListOf<List<String>>()

    private fun <T> runBatch(block: () -> T): T = withHooks { CoverTitles.collectingSingers(block) }

    /** Swaps the hooks only: the code under test must open its own burst. */
    private fun <T> withHooks(block: () -> T): T {
        val beforeSingers = CoverTitles.onSingerSeen
        val beforeBatches = CoverTitles.onSingersSeen
        CoverTitles.onSingerSeen = { singles += it }
        CoverTitles.onSingersSeen = { batches += it.toList() }
        try {
            return block()
        } finally {
            CoverTitles.onSingerSeen = beforeSingers
            CoverTitles.onSingersSeen = beforeBatches
        }
    }

    @Test
    fun `a burst is handed over once`() {
        runBatch {
            CoverTitles.read("Song One / cover by SingerA", "channel", null)
            CoverTitles.read("Song Two / cover by SingerB", "channel", null)
        }
        assertEquals(1, batches.size)
        assertEquals(listOf("SingerA", "SingerB"), batches.single())
        assertTrue(singles.isEmpty(), "no per-name write inside a burst")
    }

    @Test
    fun `a burst with a view-count fragment stores only the act`() {
        runBatch {
            CoverTitles.read("Song / cover by SEVENTEEN • 조회수 1246만회", "channel", null)
        }
        assertEquals(listOf("SEVENTEEN"), batches.single())
    }

    @Test
    fun `a burst that names nobody writes nothing`() {
        runBatch { CoverTitles.read("Ordinary Song", "channel", null) }
        assertTrue(batches.isEmpty())
        assertTrue(singles.isEmpty())
    }

    @Test
    fun `a production recents shelf writes its singers once`() {
        val shelf = withHooks {
            com.music.bitchord.ko.KoHomeFeed.recentsShelf(
                listOf(
                    com.music.bitchord.data.model.Song(
                        videoId = "burst-recents-a",
                        title = "Song One / cover by SingerA",
                        artist = "channel",
                        thumbnailUrl = null,
                    ),
                    com.music.bitchord.data.model.Song(
                        videoId = "burst-recents-b",
                        title = "Song Two / cover by SingerB",
                        artist = "channel",
                        thumbnailUrl = null,
                    ),
                ),
            )
        }
        assertEquals(2, shelf.items.size)
        assertEquals(1, batches.size, "the shelf is one burst")
        assertEquals(listOf("SingerA", "SingerB"), batches.single())
        assertTrue(singles.isEmpty(), "no per-name write while the shelf is mapped")
    }
}
