package com.music.bitchord.ko

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.lyrics.LyricsQueryVariants
import com.music.bitchord.data.lyrics.OriginalNames
import com.music.bitchord.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phone 11-17 QskmVRqgW68 "공백 - 타비 Cover(11.23)": the search artist was only
 * the singer, the singer's and the channel's names were retried first and the
 * 12 s deadline ran out before the title alone, or the original the title
 * resolved to, was ever searched.
 */
class KoCoverLyricsTest {

    private val singers = CoverTitles.knownSingers
    private val channels = CoverTitles.designatedChannels
    private val original = CoverTitles.knownOriginal

    @AfterTest
    fun restore() {
        CoverTitles.knownSingers = singers
        CoverTitles.designatedChannels = channels
        CoverTitles.knownOriginal = original
    }

    private fun song(title: String, channel: String, isVideo: Boolean = true) = Song(
        videoId = "QskmVRqgW68",
        title = title,
        artist = channel,
        thumbnailUrl = null,
        isVideo = isVideo,
    )

    @Test
    fun theCoverIsSearchedAsItsSongWithNoArtist() {
        CoverTitles.knownOriginal = { null }
        // As the queue holds it: shown as "공백" by "타비 커버", the upload kept alongside.
        val row = CoverTitles.applyTo(song("공백 - 타비 Cover(11.23)", "아라하시 타비"))
        assertEquals("공백", row.title)
        assertEquals("타비 커버", row.artist)
        val query = CoverTitles.lyricsQuery(row)
        assertEquals("공백", query.title)
        assertEquals("타비", query.artist)
        assertFalse(query.artistKnown)
        assertTrue(query.isVideo)
    }

    @Test
    fun aRealSongWithCoverInItsTitleIsLeftAlone() {
        val query = CoverTitles.lyricsQuery(song("Take Cover", "Artist", isVideo = false))
        assertEquals("Take Cover", query.title)
        assertEquals("Artist", query.artist)
        assertTrue(query.artistKnown)
    }

    @Test
    fun withoutTheOriginalOnlyTitleOnlyVariantsAreRetried() {
        val variants = LyricsQueryVariants.names("공백", "타비", artistAliases = listOf("Arahashi Tabi"))
        val extra = variants.drop(1)
        // The old retries: the singer and the channel joined, then the title alone.
        assertTrue(extra.any { it.artist.isNotBlank() }, "$extra")
        assertTrue(extra.any { it.artist.isBlank() && it.title == variants.first().title }, "$extra")

        val retried = KoCoverLyrics.variantsToRetry(extra, artistKnown = false)
        assertTrue(retried.isNotEmpty())
        assertTrue(retried.all { it.artist.isBlank() }, "$retried")
        assertEquals(extra.filter { it.artist.isBlank() }, retried)
    }

    @Test
    fun aKnownArtistKeepsEveryVariant() {
        val extra = listOf(
            LyricsQueryVariants.Name("song", "a, b"),
            LyricsQueryVariants.Name("song", ""),
        )
        assertEquals(extra, KoCoverLyrics.variantsToRetry(extra, artistKnown = true))
    }

    @Test
    fun theResolvedOriginalIsTriedFirstOnlyWithoutAnArtist() {
        val name = OriginalNames.Name(" 공백 ", " 원곡가수 ")
        assertEquals("공백" to "원곡가수", KoCoverLyrics.resolvedFirst(artistKnown = false, resolved = name))
        assertNull(KoCoverLyrics.resolvedFirst(artistKnown = true, resolved = name))
        assertNull(KoCoverLyrics.resolvedFirst(artistKnown = false, resolved = null))
        assertNull(KoCoverLyrics.resolvedFirst(artistKnown = false, resolved = OriginalNames.Name(" ", "x")))
    }

    @Test
    fun theLogLineNamesTheQueryAndNoArtist() {
        val line = KoCoverLyrics.logLine("공백", "타비", null)
        assertTrue(line.startsWith("cover-lyrics: "), line)
        assertTrue(line.contains("title='공백'"), line)
        assertTrue(line.contains("artist=none"), line)
        assertTrue(line.contains("original=none"), line)
        val found = KoCoverLyrics.logLine("공백", "타비", OriginalNames.Name("공백", "원곡가수"))
        assertTrue(found.contains("original='공백'/'원곡가수'"), found)
    }
}
