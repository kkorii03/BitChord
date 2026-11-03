package com.music.bitchord.data.lyrics

import com.music.bitchord.data.YouTubeDisplayCleaner
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phone 11-03: the artist line of a cover read someone other than its singer. */
class CoverCredit1103Test {

    private val originalSingers = CoverTitles.knownSingers
    private val originalChannels = CoverTitles.designatedChannels

    @AfterTest
    fun restore() {
        CoverTitles.knownSingers = originalSingers
        CoverTitles.designatedChannels = originalChannels
    }

    private fun song(title: String, channel: String, channelId: String? = null) = Song(
        videoId = "test",
        title = title,
        artist = channel,
        thumbnailUrl = null,
        isVideo = true,
        artistId = channelId,
    )

    @Test
    fun `an upload suffix never becomes part of the singer`() {
        listOf("가사포함", "가사", "lyrics", "MV", "풀버전").forEach { suffix ->
            val channel = "오화요 $suffix"
            val read = assertNotNull(CoverTitles.read(song("instagram (cover)", channel)), suffix)
            assertEquals("instagram", read.title, suffix)
            assertEquals("오화요", read.singer, suffix)

            val shown = YouTubeDisplayCleaner.clean(song("instagram (cover)", channel))
            assertEquals("instagram", shown.title, suffix)
            assertEquals("오화요 커버", shown.artist, suffix)
        }
    }

    @Test
    fun `a known singer upload names the other title name as the original`() {
        CoverTitles.knownSingers = { setOf("오화요", "콩밥특별시 경찰 야유회") }
        val read = assertNotNull(
            CoverTitles.read(song("콩밥특별시 경찰 야유회 - Memories(Smiling Test)", "오화요")),
        )
        assertEquals("Memories(Smiling Test)", read.title)
        assertEquals("오화요", read.singer)
        assertEquals("콩밥특별시 경찰 야유회", read.originalArtist)

        val shown = YouTubeDisplayCleaner.clean(song("콩밥특별시 경찰 야유회 - Memories(Smiling Test)", "오화요"))
        assertEquals("Memories(Smiling Test)", shown.title)
        assertEquals("콩밥특별시 경찰 야유회 · 오화요 커버", shown.artist)
        assertEquals("콩밥특별시 경찰 야유회", shown.coverOriginalArtist)
    }

    @Test
    fun `a name before Cover on the singers channel is the original, not the artist`() {
        CoverTitles.knownSingers = { setOf("오화요") }
        listOf(
            "Memories(Smiling Test) / 윤미래 cover",
            "Memories(Smiling Test) / 콩밥특별시 경찰 야유회 cover",
        ).forEach { title ->
            val read = assertNotNull(CoverTitles.read(song(title, "오화요")), title)
            assertEquals("Memories(Smiling Test)", read.title, title)
            assertEquals("오화요", read.singer, title)
            assertEquals(title.substringAfter(" / ").removeSuffix(" cover"), read.originalArtist, title)

            val shown = YouTubeDisplayCleaner.clean(song(title, "오화요"))
            assertEquals("Memories(Smiling Test)", shown.title, title)
            assertEquals("${title.substringAfter(" / ").removeSuffix(" cover")} · 오화요 커버", shown.artist, title)
        }
    }

    @Test
    fun `the reported cover title shapes keep their singer`() {
        CoverTitles.knownSingers = { setOf("오화요") }

        val by = assertNotNull(CoverTitles.read(song("Song / cover by X", "오화요")))
        assertEquals("Song", by.title)
        assertEquals("X", by.singer)

        val bare = assertNotNull(CoverTitles.read(song("Song (cover)", "오화요")))
        assertEquals("Song", bare.title)
        assertEquals("오화요", bare.singer)

        val originalFirst = assertNotNull(CoverTitles.read(song("Original Artist - Song / cover", "오화요")))
        assertEquals("Song", originalFirst.title)
        assertEquals("Original Artist", originalFirst.originalArtist)
        assertEquals("오화요", originalFirst.singer)
    }

    @Test
    fun `a version word before Cover is not the original artist on a known singers channel`() {
        CoverTitles.knownSingers = { setOf("오화요") }
        val read = assertNotNull(CoverTitles.read(song("Song - Live / 윤미래 cover", "오화요")))
        assertEquals("Song", read.title)
        assertEquals("윤미래", read.originalArtist)
        assertEquals("오화요", read.singer)
    }

    @Test
    fun `a dash credit before Cover keeps the reversed lyrics query`() {
        CoverTitles.knownSingers = { setOf("오화요") }
        val upload = song("Song - 진짜가수 / 윤미래 cover", "오화요")
        val read = assertNotNull(CoverTitles.read(upload))
        assertEquals("Song", read.title)
        assertEquals("진짜가수", read.originalArtist)
        assertEquals("오화요", read.singer)
        assertTrue(read.orderUnsure)
        assertTrue(CoverTitles.lyricsQuery(upload).alternatives.contains("진짜가수" to "Song"))
    }

    @Test
    fun `a cover whose singer equals the original still falls back to the uploader`() {
        CoverTitles.knownSingers = { setOf("윤미래") }
        val upload = song("Song - 윤미래 / 윤미래 cover", "윤미래")
        val read = assertNotNull(CoverTitles.read(upload))
        assertEquals("윤미래", read.singer)
        assertEquals("윤미래", read.originalArtist)
        assertEquals("윤미래 커버", YouTubeDisplayCleaner.clean(upload).artist)
    }

    @Test
    fun `a stored upload suffix is dropped from the learned singer set`() {
        CoverTitles.knownSingers = { setOf("오화요 가사포함") }
        val read = assertNotNull(CoverTitles.read(song("Memories(Smiling Test) / 윤미래 cover", "오화요 가사포함")))
        assertEquals("Memories(Smiling Test)", read.title)
        assertEquals("오화요", read.singer)
        assertEquals("윤미래", read.originalArtist)
    }

    @Test
    fun `a no-dash bracket credit on a cover channel is the original artist`() {
        CoverTitles.designatedChannels = { setOf("chan-ohwayo") }
        val upload = song("Tomboy((G)I-DLE)", "오화요", "chan-ohwayo")

        val read = assertNotNull(CoverTitles.read(upload))
        assertEquals("Tomboy", read.title)
        assertEquals("(G)I-DLE", read.originalArtist)
        assertEquals("오화요", read.singer)

        val shown = YouTubeDisplayCleaner.clean(upload)
        assertEquals("Tomboy", shown.title)
        assertEquals("(G)I-DLE · 오화요 커버", shown.artist)

        val query = CoverTitles.lyricsQuery(upload)
        assertEquals("Tomboy", query.title)
        assertEquals("(G)I-DLE", query.artist)
    }

    @Test
    fun `a dashed B title is still read the B way on a designated channel`() {
        CoverTitles.designatedChannels = { setOf("chan-lena") }
        val read = assertNotNull(
            CoverTitles.read("달콤레나 - 고추잠자리(조용필)", "레나의 노래 일기", "chan-lena"),
        )
        assertEquals("고추잠자리", read.title)
        assertEquals("조용필", read.originalArtist)
        assertEquals("달콤레나", read.singer)
    }

    @Test
    fun `an old row saved with a cover suffix still reads as the song and byline`() {
        val stored = song("Shape of You (cover)", "오화요")
            .copy(coverOriginalArtist = "Ed Sheeran")

        val shown = com.music.bitchord.data.StoredSongDisplay.clean(stored)

        assertEquals("Shape of You", shown.title)
        assertEquals("Ed Sheeran · 오화요 커버", shown.artist)
    }

    @Test
    fun `an own-channel song with a translated bracket has no original credit`() {
        CoverTitles.knownSingers = { setOf("아카네 리제") }
        val read = CoverTitles.read("아카네 리제 - しわあわせ(주름 행복)", "아카네 리제", null)
        assertNull(read)
    }
}

/** A cover that was matched to the original's catalogue release still shows its singer. */
class CatalogueMatchedCoverCreditTest {

    private val originalSingers = CoverTitles.knownSingers

    @AfterTest
    fun restore() {
        CoverTitles.knownSingers = originalSingers
    }

    private fun song(title: String, artist: String, albumId: String?) = Song(
        videoId = "test",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        albumId = albumId,
        isVideo = true,
    )

    @Test
    fun `a stored catalogue-matched cover keeps the uploader as the artist`() {
        CoverTitles.knownSingers = { setOf("오화요") }
        val stored = song("Memories(Smiling Test) / cover by 오화요", "윤미래", "MPREb_original")

        val shown = com.music.bitchord.data.StoredSongDisplay.clean(stored)

        assertEquals("Memories(Smiling Test)", shown.title)
        assertEquals("오화요 커버", shown.artist)
    }

    @Test
    fun `a fresh catalogue-matched cover drops the upload suffix`() {
        CoverTitles.knownSingers = { setOf("오화요") }
        val upload = song("instagram (cover)", "오화요 가사포함", "MPREb_original")

        val shown = YouTubeDisplayCleaner.cleanUpload(upload)

        assertEquals("instagram", shown.title)
        assertEquals("오화요 커버", shown.artist)
    }
}
