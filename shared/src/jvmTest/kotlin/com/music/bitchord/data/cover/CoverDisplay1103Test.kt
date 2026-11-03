package com.music.bitchord.data.cover

import com.music.bitchord.data.StoredSongDisplay
import com.music.bitchord.data.YouTubeDisplayCleaner
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Phone 11-03: a cover reads as its song, original artist and singer again. */
class CoverDisplay1103Test {

    private val originalSingers = CoverTitles.knownSingers

    @AfterTest
    fun restore() {
        CoverTitles.knownSingers = originalSingers
    }

    private fun song(
        title: String,
        channel: String,
        albumId: String? = null,
        isVideo: Boolean = true,
    ) = Song(
        videoId = "test",
        title = title,
        artist = channel,
        thumbnailUrl = null,
        albumId = albumId,
        isVideo = isVideo,
    )

    // The user's own example (11-03): title is the original title only, artist
    // is '<original artist> · <singer> 커버', no '(cover)' anywhere.
    @Test
    fun `Tomboy by 오화요 reads as the user specified`() {
        val shown = StoredSongDisplay.clean(song("Tomboy((G)I-DLE)", "오화요"))
        assertEquals("Tomboy", shown.title)
        assertEquals("(G)I-DLE · 오화요 커버", shown.artist)
    }

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
            assertEquals("${read.originalArtist} · 오화요 커버", shown.artist, title)
        }
    }

    @Test
    fun `the reported cover title shapes keep their singer`() {
        CoverTitles.knownSingers = { setOf("오화요") }

        val by = assertNotNull(CoverTitles.read(song("Song / cover by X", "오화요")))
        assertEquals("Song", by.title)
        assertEquals("X", by.singer)
        assertEquals("X 커버", YouTubeDisplayCleaner.clean(song("Song / cover by X", "오화요")).artist)

        val bare = assertNotNull(CoverTitles.read(song("Song (cover)", "오화요")))
        assertEquals("Song", bare.title)
        assertEquals("오화요", bare.singer)
        assertEquals("오화요 커버", YouTubeDisplayCleaner.clean(song("Song (cover)", "오화요")).artist)

        val originalFirst = assertNotNull(CoverTitles.read(song("Original Artist - Song / cover", "오화요")))
        assertEquals("Song", originalFirst.title)
        assertEquals("Original Artist", originalFirst.originalArtist)
        assertEquals("오화요", originalFirst.singer)
        assertEquals(
            "Original Artist · 오화요 커버",
            YouTubeDisplayCleaner.clean(song("Original Artist - Song / cover", "오화요")).artist,
        )
    }

    @Test
    fun `the reported Tomboy cover reads the bracketed original as its artist`() {
        CoverTitles.knownSingers = { setOf("오화요") }
        listOf("Tomboy((G)I-DLE) (cover)", "Tomboy((G)I-DLE) 커버").forEach { title ->
            val cover = song(title, "오화요")
            val read = assertNotNull(CoverTitles.read(cover), title)
            assertEquals("Tomboy", read.title, title)
            assertEquals("(G)I-DLE", read.originalArtist, title)
            assertEquals("오화요", read.singer, title)

            val shown = YouTubeDisplayCleaner.clean(cover)
            assertEquals("Tomboy", shown.title, title)
            assertEquals("(G)I-DLE · 오화요 커버", shown.artist, title)

            val lyrics = CoverTitles.lyricsQuery(cover)
            assertEquals("Tomboy", lyrics.title, title)
            assertEquals("(G)I-DLE", lyrics.artist, title)
        }
    }

    @Test
    fun `a catalogue-matched cover still shows its singer`() {
        CoverTitles.knownSingers = { setOf("오화요") }
        val stored = song(
            "윤미래 - Memories(Smiling Test) / cover by 오화요",
            "윤미래",
            albumId = "MPREb_original",
        )

        val shown = StoredSongDisplay.clean(stored)

        assertEquals("Memories(Smiling Test)", shown.title)
        assertEquals("윤미래 · 오화요 커버", shown.artist)
    }
}
