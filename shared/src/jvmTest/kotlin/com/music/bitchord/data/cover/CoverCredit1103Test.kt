package com.music.bitchord.data.cover

import com.music.bitchord.data.StoredSongDisplay
import com.music.bitchord.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Phone 11-03, playlist '오화요(허니즈)': the old fork showed a cover as the
 * original song and "원곡자 · 가수 커버", never "(cover)" or a channel tag as
 * the singer. The uploader is the singer when the title names the original.
 */
class CoverCredit1103Test {

    private val singers = CoverTitles.knownSingers
    private val channels = CoverTitles.designatedChannels

    @AfterTest
    fun restore() {
        CoverTitles.knownSingers = singers
        CoverTitles.designatedChannels = channels
    }

    private fun song(title: String, channel: String) = Song(
        videoId = "v",
        title = title,
        artist = channel,
        thumbnailUrl = null,
        isVideo = true,
    )

    @Test
    fun `the reported Tomboy cover shows the original title and the old byline`() {
        val upload = song("Tomboy((G)I-DLE) (cover)", "오화요")
        val shown = StoredSongDisplay.clean(upload)

        assertEquals("Tomboy", shown.title)
        assertEquals("(G)I-DLE · 오화요 커버", shown.artist)
        assertEquals("오화요", shown.coverSinger)
        assertEquals("(G)I-DLE", shown.coverOriginalArtist)

        val query = CoverTitles.lyricsQuery(upload)
        assertEquals("Tomboy", query.title)
        assertEquals("(G)I-DLE", query.artist)

        // The player hands lyrics the shown row, whose upload title is kept.
        val shownQuery = CoverTitles.lyricsQuery(CoverTitles.applyTo(upload))
        assertEquals("Tomboy", shownQuery.title)
        assertEquals("(G)I-DLE", shownQuery.artist)
    }

    @Test
    fun `Tomboy without a cover word is read on the singer's channel`() {
        CoverTitles.knownSingers = { setOf("오화요") }
        val read = assertNotNull(CoverTitles.read("Tomboy((G)I-DLE)", "오화요", null))
        assertEquals("Tomboy", read.title)
        assertEquals("(G)I-DLE", read.originalArtist)
        assertEquals("오화요", read.singer)
    }

    @Test
    fun `an upload tag is not part of the singer`() {
        val shown = StoredSongDisplay.clean(song("instagram (cover)", "오화요 가사포함"))

        assertEquals("instagram", shown.title)
        assertEquals("오화요 커버", shown.artist)
        assertEquals("오화요", shown.coverSinger)
    }

    @Test
    fun `every upload tag is stripped from the singer`() {
        for (tag in listOf("가사포함", "가사", "lyrics", "MV", "풀버전")) {
            val shown = StoredSongDisplay.clean(song("Song (cover)", "오화요 $tag"))
            assertEquals("Song", shown.title, tag)
            assertEquals("오화요 커버", shown.artist, tag)
        }
    }

    @Test
    fun `the original artist after cover is not the singer`() {
        CoverTitles.knownSingers = { setOf("오화요") }
        val shown = StoredSongDisplay.clean(song("Memories(Smiling Test) / cover by 윤미래", "오화요"))

        assertEquals("Memories(Smiling Test)", shown.title)
        assertEquals("윤미래 · 오화요 커버", shown.artist)
        assertEquals("오화요", shown.coverSinger)
        assertEquals("윤미래", shown.coverOriginalArtist)
    }

    @Test
    fun `a playlist name is never taken as the singer`() {
        CoverTitles.knownSingers = { setOf("오화요") }
        val shown = StoredSongDisplay.clean(song("Song / 콩밥특별시 경찰 야유회 cover", "오화요"))

        assertEquals("Song", shown.title)
        assertEquals("오화요", shown.coverSinger)
        assertNotEquals("콩밥특별시 경찰 야유회", shown.coverSinger)
    }

    @Test
    fun `a cover row saved by the older build is shown again from its upload`() {
        val legacy = Song(
            videoId = "v",
            title = "Tomboy((G)I-DLE) (cover)",
            artist = "오화요",
            thumbnailUrl = null,
            isVideo = true,
            originalTitle = "Tomboy((G)I-DLE) (cover)",
            originalArtist = "오화요",
            coverSinger = "오화요",
        )

        val shown = StoredSongDisplay.clean(legacy)

        assertEquals("Tomboy", shown.title)
        assertEquals("(G)I-DLE · 오화요 커버", shown.artist)
        assertEquals("(G)I-DLE", shown.coverOriginalArtist)
    }

    @Test
    fun `a legacy cover row that lost its video flag still shows the byline`() {
        val legacy = Song(
            videoId = "v",
            title = "instagram (cover)",
            artist = "오화요 가사포함",
            thumbnailUrl = null,
            isVideo = false,
            originalTitle = "instagram (cover)",
            originalArtist = "오화요 가사포함",
        )

        val shown = StoredSongDisplay.clean(legacy)

        assertEquals("instagram", shown.title)
        assertEquals("오화요 커버", shown.artist)
        assertFalse(shown.isVideo)
    }

    @Test
    fun `a legacy row that lost its video flag reads its original credit too`() {
        val legacy = Song(
            videoId = "v",
            title = "Original Artist - Song (cover)",
            artist = "오화요",
            thumbnailUrl = null,
            isVideo = false,
            originalTitle = "Original Artist - Song (cover)",
            originalArtist = "오화요",
        )

        val shown = StoredSongDisplay.clean(legacy)

        assertEquals("Song", shown.title)
        assertEquals("Original Artist · 오화요 커버", shown.artist)
        assertFalse(shown.isVideo)
    }

    @Test
    fun `the three reported title shapes keep the old fork credit`() {
        val by = StoredSongDisplay.clean(song("Song / cover by X", "channel"))
        assertEquals("Song", by.title)
        assertEquals("X 커버", by.artist)

        val bare = StoredSongDisplay.clean(song("Song (cover)", "오화요"))
        assertEquals("Song", bare.title)
        assertEquals("오화요 커버", bare.artist)

        val original = StoredSongDisplay.clean(song("Original Artist - Song / cover", "오화요"))
        assertEquals("Song", original.title)
        assertEquals("Original Artist · 오화요 커버", original.artist)
    }
}
