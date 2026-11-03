package com.music.bitchord.data.lyrics

import com.music.bitchord.data.StoredSongDisplay
import com.music.bitchord.data.YouTubeDisplayCleaner
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phone 11-03: the "오화요(허니즈)" playlist is covers uploaded by 오화요, but
 * 1.9 showed "Tomboy((G)I-DLE) (cover)" / "오화요" and leaked uploader tags
 * ("가사포함") or another channel's text into the artist line.
 */
class CoverCredit1103Test {

    private val channels = CoverTitles.designatedChannels
    private val singers = CoverTitles.knownSingers
    private val seen = CoverTitles.onSingerSeen
    private val original = CoverTitles.knownOriginal

    @AfterTest
    fun restore() {
        CoverTitles.designatedChannels = channels
        CoverTitles.knownSingers = singers
        CoverTitles.onSingerSeen = seen
        CoverTitles.knownOriginal = original
    }

    private fun song(
        title: String,
        channel: String,
        artistId: String? = "ohwayo",
        isVideo: Boolean = true,
    ) = Song(
        videoId = "cover-1103",
        title = title,
        artist = channel,
        thumbnailUrl = null,
        artistId = artistId,
        isVideo = isVideo,
    )

    private fun designated() {
        CoverTitles.designatedChannels = { setOf("ohwayo") }
        CoverTitles.knownSingers = { emptySet() }
    }

    @Test
    fun `tomboy keeps the original title and credits both people`() {
        designated()
        val upload = song("Tomboy((G)I-DLE)", "오화요")

        val cover = assertNotNull(CoverTitles.read(upload))
        assertEquals("Tomboy", cover.title)
        assertEquals("(G)I-DLE", cover.originalArtist)
        assertEquals("오화요", cover.singer)
        assertEquals("(G)I-DLE · 오화요 커버", cover.byline)

        val applied = CoverTitles.applyTo(upload)
        assertEquals("Tomboy", applied.title)
        assertEquals("(G)I-DLE · 오화요 커버", applied.artist)
        assertEquals("Tomboy((G)I-DLE)", applied.originalTitle)
        assertEquals("오화요", applied.coverSinger)
        assertEquals("(G)I-DLE", applied.coverOriginalArtist)

        val shown = StoredSongDisplay.clean(upload)
        assertEquals("Tomboy", shown.title)
        assertEquals("(G)I-DLE · 오화요 커버", shown.artist)
    }

    @Test
    fun `a learned cover singer's own channel also reads the bracket title`() {
        CoverTitles.designatedChannels = { emptySet() }
        CoverTitles.knownSingers = { setOf("오화요") }

        val cover = assertNotNull(CoverTitles.read(song("Tomboy((G)I-DLE)", "오화요")))
        assertEquals("Tomboy", cover.title)
        assertEquals("(G)I-DLE", cover.originalArtist)
        assertEquals("오화요", cover.singer)
    }

    @Test
    fun `lyrics search uses the original title and artist`() {
        designated()
        val query = CoverTitles.lyricsQuery(song("Tomboy((G)I-DLE)", "오화요"))

        assertEquals("Tomboy", query.title)
        assertEquals("(G)I-DLE", query.artist)
        assertTrue(query.artistKnown)
        assertTrue(query.isVideo)
    }

    @Test
    fun `the uploader tag is not part of the singer`() {
        val shown = YouTubeDisplayCleaner.clean(song("instagram (cover)", "오화요 가사포함"))

        assertEquals("instagram", shown.title)
        assertEquals("오화요 커버", shown.artist)
        assertEquals("오화요", shown.coverSinger)
    }

    @Test
    fun `every uploader tag is stripped from the singer`() {
        for (suffix in listOf("가사포함", "가사", "lyrics", "MV", "풀버전")) {
            val shown = YouTubeDisplayCleaner.clean(song("instagram (cover)", "오화요 $suffix"))
            assertEquals("instagram", shown.title, suffix)
            assertEquals("오화요 커버", shown.artist, suffix)
        }
    }

    @Test
    fun `a name after the slash is the original when the uploader sings`() {
        designated()
        val shown = YouTubeDisplayCleaner.clean(song("Song / 콩밥특별시 경찰 야유회 Cover", "오화요"))

        assertEquals("Song", shown.title)
        assertEquals("콩밥특별시 경찰 야유회 · 오화요 커버", shown.artist)
        assertEquals("오화요", CoverTitles.creditedArtist(shown))
    }

    @Test
    fun `a bracketed cover tag after the original artist keeps the song clean`() {
        designated()
        val shown = YouTubeDisplayCleaner.clean(song("콩밥특별시 경찰 야유회 - Song (cover)", "오화요"))

        assertEquals("Song", shown.title)
        assertEquals("콩밥특별시 경찰 야유회 · 오화요 커버", shown.artist)
        assertEquals("오화요", CoverTitles.creditedArtist(shown))
    }

    @Test
    fun `the original artist is never the cover's artist`() {
        designated()
        val shown = YouTubeDisplayCleaner.clean(song("Memories(Smiling Test) / 윤미래 cover", "오화요"))

        assertTrue(shown.title.startsWith("Memories"), shown.title)
        assertEquals("윤미래 · 오화요 커버", shown.artist)
        assertEquals("오화요", CoverTitles.creditedArtist(shown))
        assertEquals("윤미래", CoverTitles.creditedOriginalArtist(shown))
    }

    @Test
    fun `the three reported title shapes keep the 18 display`() {
        designated()

        val by = YouTubeDisplayCleaner.clean(song("Song / cover by 오화요", "오화요"))
        assertEquals("Song", by.title)
        assertEquals("오화요 커버", by.artist)

        val bare = YouTubeDisplayCleaner.clean(song("Song (cover)", "오화요"))
        assertEquals("Song", bare.title)
        assertEquals("오화요 커버", bare.artist)

        val credited = YouTubeDisplayCleaner.clean(song("Original Artist - Song / cover", "오화요"))
        assertEquals("Song", credited.title)
        assertEquals("Original Artist · 오화요 커버", credited.artist)
    }

    @Test
    fun `a row saved with the old cover suffix is repaired`() {
        designated()
        val old = song("Tomboy((G)I-DLE) (cover)", "오화요").copy(
            originalTitle = "Tomboy((G)I-DLE)",
            originalArtist = "오화요",
            coverSinger = "오화요",
        )

        val applied = CoverTitles.applyTo(old)

        assertEquals("Tomboy", applied.title)
        assertEquals("(G)I-DLE · 오화요 커버", applied.artist)
        assertEquals("오화요", applied.coverSinger)
        assertEquals("(G)I-DLE", applied.coverOriginalArtist)
    }
}
