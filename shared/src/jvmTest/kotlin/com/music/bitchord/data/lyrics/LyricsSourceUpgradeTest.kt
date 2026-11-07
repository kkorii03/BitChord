package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsSourceUpgradeTest {
    private fun named(vararg names: String) = names.map { name -> LyricsSource.entries.first { it.name == name } }

    private val oldDefault = named(
        "AMLL_TTML", "LRC_RED", "BINI_LYRICS", "BETTER_LYRICS", "BETTER_LYRICS_PORTATO", "PAXSENIX",
        "PAXSENIX_SPOTIFY", "PAXSENIX_MUSIXMATCH", "LYRICS_PLUS", "VIBE", "BUGS", "UNISON",
        "YOUTUBE_TRANSCRIPT", "YOUTUBE_MUSIC", "KUGOU", "LRCLIB", "MUSIXMATCH", "GENIUS",
    )

    @Test
    fun `word-timed KuGou comes before the line-timed services and fallbacks by default`() {
        val order = LyricsSource.offered
        val kugou = order.indexOf(LyricsSource.KUGOU)
        val lineTimed = listOf(
            LyricsSource.VIBE, LyricsSource.BUGS, LyricsSource.YOUTUBE_TRANSCRIPT, LyricsSource.YOUTUBE_MUSIC,
            LyricsSource.LRCLIB, LyricsSource.MUSIXMATCH, LyricsSource.GENIUS,
        )
        assertTrue(lineTimed.all { order.indexOf(it) > kugou }, "order $order")
    }

    @Test
    fun `an old default nobody changed becomes today's`() {
        assertEquals(LyricsSource.offered, LyricsSource.upgraded(oldDefault))
    }

    @Test
    fun `an old default saved by a build that already had some new sources also becomes today's`() {
        val savedWithSome = oldDefault.take(9) + listOf(LyricsSource.SYNCLRC, LyricsSource.QQ_MUSIC) + oldDefault.drop(9)
        assertEquals(LyricsSource.offered, LyricsSource.upgraded(savedWithSome))
    }

    @Test
    fun `an order the listener made is kept, with new sources slotted in`() {
        val mine = listOf(LyricsSource.LRCLIB) + oldDefault.filterNot { it == LyricsSource.LRCLIB }
        val upgraded = LyricsSource.upgraded(mine)
        assertEquals(LyricsSource.LRCLIB, upgraded.first())
        assertTrue(LyricsSource.QQ_MUSIC in upgraded && LyricsSource.SYNCLRC in upgraded)
        assertTrue(upgraded.indexOf(LyricsSource.KUGOU) > upgraded.indexOf(LyricsSource.YOUTUBE_MUSIC))
    }
}
