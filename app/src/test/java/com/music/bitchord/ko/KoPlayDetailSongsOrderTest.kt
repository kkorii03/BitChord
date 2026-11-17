package com.music.bitchord.ko

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [KO] C6a-2: a tap on a sorted page, a revealed hidden video or a suggested
 * row must play the row that was tapped, in the order shown on screen.
 *
 * Every case here taps a row that is not in the page's own listing (or an
 * ARTIST page), so the bounded check stops before it reads the Cast state.
 */
class KoPlayDetailSongsOrderTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "x", thumbnailUrl = null)

    private val a = song("A")
    private val b = song("B")
    private val c = song("C")
    private val h = song("H")

    private fun page(type: BrowseType, suggested: List<Song> = emptyList()) = DetailPage(
        browseId = "UCx",
        title = "t",
        subtitle = "",
        thumbnailUrl = null,
        songs = UiState.Success(listOf(a, b, c)),
        type = type,
        hiddenSongs = listOf(h),
        suggestedSongs = suggested,
    )

    private fun play(page: DetailPage, songs: List<Song>, index: Int): Pair<List<Song>, Int> {
        var captured: Pair<List<Song>, Int>? = null
        runBlocking {
            koPlayDetailSongs(
                page = page,
                songs = songs,
                index = index,
                isCurrentRequest = { true },
                takePlaybackContext = { page },
                awaitDetailListing = { page },
                withAlbum = { it },
                playFrom = { q, i, _, _, _ ->
                    captured = q to i
                    null
                },
            )
        }
        return checkNotNull(captured) { "nothing was played" }
    }

    @Test
    fun `revealed hidden video plays itself, not the row at its index in the page`() {
        val (queue, i) = play(page(BrowseType.PLAYLIST), listOf(c, b, a, h), 3)
        assertEquals("H", queue[i].videoId)
        assertEquals(listOf("C", "B", "A", "H"), queue.map { it.videoId })
    }

    @Test
    fun `sorted artist page keeps the order on screen around the tapped song`() {
        val (queue, i) = play(page(BrowseType.ARTIST), listOf(c, b, a), 0)
        assertEquals("C", queue[i].videoId)
        assertEquals(listOf("C", "B", "A"), queue.map { it.videoId })
    }

    @Test
    fun `suggested row plays itself`() {
        val s1 = song("S1")
        val s2 = song("S2")
        val (queue, i) = play(page(BrowseType.PLAYLIST, listOf(s1, s2)), listOf(s1, s2), 1)
        assertEquals("S2", queue[i].videoId)
    }
}
