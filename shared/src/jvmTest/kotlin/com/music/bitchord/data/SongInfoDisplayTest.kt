package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongInfoDisplayTest {
    private fun song(
        videoId: String = "abcdefghijk",
        album: String? = null,
        localUri: String? = null,
    ) = Song(
        videoId = videoId,
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
        albumName = album,
        localUri = localUri,
    )

    @Test
    fun `known release facts lead and missing youtube counts are omitted`() {
        val lines = SongInfoDisplay.lines(
            info = SongInfo(
                album = "Album",
                releaseDate = "2024-01-02",
                views = "1234",
                comments = "5",
            ),
            song = song(),
            quality = null,
            unknownLabel = "No information",
        )

        assertEquals(
            listOf(
                SongInfoField.ALBUM,
                SongInfoField.RELEASE_DATE,
                SongInfoField.VIEWS,
                SongInfoField.COMMENTS,
            ),
            lines.map { it.field },
        )
        assertEquals("1.2K", lines.first { it.field == SongInfoField.VIEWS }.value)
        assertTrue(lines.none { it.field == SongInfoField.LIKES })
    }

    @Test
    fun `genuinely missing album and release are grey no-information rows`() {
        val lines = SongInfoDisplay.lines(
            info = SongInfo(),
            song = song(),
            quality = null,
            unknownLabel = "No information",
        )

        assertEquals(listOf(SongInfoField.ALBUM, SongInfoField.RELEASE_DATE), lines.map { it.field })
        assertTrue(lines.all { it.value == "No information" && it.unknown })
    }

    @Test
    fun `the row's own album fills the panel before the request answers`() {
        val lines = SongInfoDisplay.lines(
            info = SongInfo(),
            song = song(album = "Known album"),
            quality = null,
            unknownLabel = "No information",
        )

        val album = lines.first { it.field == SongInfoField.ALBUM }
        assertEquals("Known album", album.value)
        assertFalse(album.unknown)
    }

    @Test
    fun `genre credits and actual quality appear only when known`() {
        val lines = SongInfoDisplay.lines(
            info = SongInfo(genre = "Pop", composer = "A", lyricist = "B"),
            song = song(),
            quality = "AAC · 339 kbps",
            unknownLabel = "No information",
        )

        assertEquals("Pop", lines.first { it.field == SongInfoField.GENRE }.value)
        assertEquals("A", lines.first { it.field == SongInfoField.COMPOSER }.value)
        assertEquals("B", lines.first { it.field == SongInfoField.LYRICIST }.value)
        assertEquals("AAC · 339 kbps", lines.first { it.field == SongInfoField.QUALITY }.value)
    }

    @Test
    fun `loading shows only the two required rows`() {
        val lines = SongInfoDisplay.loadingLines(song(album = "Known album"))

        assertEquals(listOf(SongInfoField.ALBUM, SongInfoField.RELEASE_DATE), lines.map { it.field })
        assertEquals("Known album", lines.first().value)
        assertNull(lines.last().value)
    }

    @Test
    fun `only real youtube ids are requested directly`() {
        assertEquals("abcdefghijk", SongInfoTarget.youtubeId(song()))
        assertNull(SongInfoTarget.youtubeId(song(videoId = "src:config::track")))
        assertNull(SongInfoTarget.youtubeId(song(videoId = "content://media/1")))
        assertNull(SongInfoTarget.youtubeId(song(videoId = "local-file", localUri = "file:///a.mp3")))
    }

    @Test
    fun `source-backed rows get a catalogue lookup but local files do not`() {
        assertTrue(SongInfoTarget.needsCatalogueLookup(song(videoId = "src:config::track")))
        assertFalse(SongInfoTarget.needsCatalogueLookup(song(videoId = "local-file", localUri = "file:///a.mp3")))
    }

    @Test
    fun `quality describes the stream actually playing`() {
        val snapshot = NerdStats.Snapshot(
            mimeType = "audio/mp4a-latm",
            bitrateKbps = 339,
            sampleRateHz = 44_100,
            channels = 2,
            mediaId = "abcdefghijk",
        )
        val label = SongInfoQuality.label(song(), snapshot)
        assertEquals("AAC · 44.1 kHz · 339 kbps", label)
    }

    @Test
    fun `a snapshot for another track is not shown`() {
        val snapshot = NerdStats.Snapshot(
            mimeType = "audio/flac",
            bitrateKbps = null,
            sampleRateHz = 96_000,
            channels = 2,
            mediaId = "othertrack1",
        )
        assertNull(SongInfoQuality.label(song(), snapshot))
    }

    @Test
    fun `a downloaded file's own format is shown when it is not playing`() {
        val downloaded = song().copy(downloadFormat = "FLAC")
        assertEquals("FLAC", SongInfoQuality.label(downloaded, null))
    }
}
