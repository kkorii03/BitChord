package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SearchTabSources
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.telegram.TelegramTrack
import com.music.bitchord.telegram.TelegramTrackMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search-row conversion, ranking and the Search tab wiring for the Telegram source. */
class TelegramSourceStageTwoTest {

    private fun track(
        title: String = "Paniyon Sa",
        artist: String = "Atif Aslam",
        fileName: String = "Paniyon Sa.flac",
        mime: String = "audio/flac",
        duration: Int = 245,
    ) = TelegramTrack(
        id = "-100123_42",
        chatId = -100123,
        messageId = 42,
        fileId = 77,
        size = 30_000_000,
        title = title,
        artist = artist,
        durationSec = duration,
        format = fileName.substringAfterLast('.'),
        mimeType = mime,
        fileName = fileName,
    )

    @Test
    fun `search row becomes a song with the source key and runtime`() {
        val song = TelegramTrackMapper.toSong(track(), configId = "telegram-config")

        assertEquals("Paniyon Sa", song.title)
        assertEquals("Atif Aslam", song.artist)
        assertEquals("4:05", song.durationText)
        assertTrue(song.videoId.startsWith("src:telegram-config::"))
        assertEquals("src:telegram-config::-100123_42", song.videoId)
    }

    @Test
    fun `lossless files are labelled lossless and lossy files are not`() {
        assertEquals("LOSSLESS", TelegramTrackMapper.toSong(track(fileName = "a.flac"), "c").sourceQuality)
        assertEquals("LOSSLESS", TelegramTrackMapper.toSong(track(fileName = "a.wav"), "c").sourceQuality)
        assertEquals("LOSSLESS", TelegramTrackMapper.toSong(track(fileName = "a.alac"), "c").sourceQuality)
        assertEquals(
            "LOSSLESS",
            TelegramTrackMapper.toSong(track(fileName = "a ALAC.m4a"), "c").sourceQuality,
        )
        assertEquals("HIGH", TelegramTrackMapper.toSong(track(fileName = "a.mp3"), "c").sourceQuality)
        assertEquals(
            "LOSSLESS",
            TelegramTrackMapper.toSong(track(fileName = "no-extension", mime = "audio/x-flac"), "c").sourceQuality,
        )
    }

    @Test
    fun `stream format carries the codec so HQ verification can run`() {
        val flac = TelegramTrackMapper.streamFormat(track(fileName = "a.flac"))
        assertEquals("flac", flac.codec)
        assertEquals(true, flac.isLossless)
        assertEquals("mp3", TelegramTrackMapper.streamFormat(track(fileName = "a.mp3")).codec)
    }

    @Test
    fun `telegram ranks above youtube`() {
        val ordered = listOf(SourceKind.YOUTUBE, SourceKind.TELEGRAM).sortedBy { it.rank }
        assertEquals(listOf(SourceKind.TELEGRAM, SourceKind.YOUTUBE), ordered)
        assertTrue(SourceKind.TELEGRAM.rank < SourceKind.YOUTUBE.rank)
    }

    @Test
    fun `telegram is a built-in that starts switched off`() {
        val sources = SourceRegistry.sourcesForInit(emptyList(), forceJioSaavnOff = true)

        val telegram = sources.single { it.kind == SourceKind.TELEGRAM }
        assertFalse(telegram.enabled)
        assertTrue(sources.single { it.kind == SourceKind.YOUTUBE }.enabled)
        assertFalse(SourceConfig(kind = SourceKind.TELEGRAM).enabled)
    }

    @Test
    fun `the search tab asks addons and telegram but not youtube`() {
        val filtered = SearchTabSources.searchable(
            listOf(
                FakeSource("addon", SourceKind.ADDON),
                FakeSource("telegram", SourceKind.TELEGRAM),
                FakeSource("youtube", SourceKind.YOUTUBE),
            ),
        )

        assertEquals(listOf(SourceKind.ADDON, SourceKind.TELEGRAM), filtered.map { it.kind })
    }

    private class FakeSource(
        override val configId: String,
        override val kind: SourceKind,
    ) : MusicSource {
        override val displayName: String get() = configId
        override suspend fun health(): SourceHealth = SourceHealth.Ok()
        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> = emptyList()

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? = null
    }
}
