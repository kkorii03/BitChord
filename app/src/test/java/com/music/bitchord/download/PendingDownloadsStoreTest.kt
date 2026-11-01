package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The waiting download queue has to survive the process being killed while
 * the app is in the background. It used to live only in a `LinkedHashMap`, so
 * a restart silently dropped every track the listener had already asked for.
 */
class PendingDownloadsStoreTest {

    @Test
    fun `a queued download and its pinned options survive a restart`() {
        val song = Song(
            videoId = "video-1",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = "https://example.test/art.jpg",
            durationText = "3:21",
            albumName = "Album",
            isVideo = true,
            isVideoOrigin = true,
            typeUnknown = false,
            playbackSource = "source-config",
            playbackSourceId = "track-1",
        )
        val hq = HqDownloadSource(
            sourceConfigId = "source-config",
            trackId = "track-1",
            tier = HqTier.HI_RES_24_44,
            offsetMs = 120L,
            rate = 1.01,
            sourceTitle = "Source Title",
            sourceArtist = "Source Artist",
        )
        val entry = PendingDownloadsStore.PendingEntry(
            song = PendingDownloadsStore.storedSong(song),
            quality = DownloadQuality.LOSSLESS.name,
            hq = PendingDownloadsStore.storedHq(hq),
            avoidSource = "avoid-1",
            forceReplace = true,
            youtubeOnly = true,
            minimumTier = HqTier.LOSSLESS_16.name,
            automatic = true,
            mvSoundRepair = true,
            allowMetered = true,
        )

        val decoded = PendingDownloadsStore.decode(
            PendingDownloadsStore.encode(listOf(entry)),
        )

        assertEquals(1, decoded.size)
        val restored = decoded.single()
        val restoredSong = PendingDownloadsStore.restoredSong(restored.song)
        assertEquals(song.videoId, restoredSong.videoId)
        assertEquals(song.title, restoredSong.title)
        assertEquals(song.artist, restoredSong.artist)
        assertEquals(song.thumbnailUrl, restoredSong.thumbnailUrl)
        assertEquals(song.durationText, restoredSong.durationText)
        assertEquals(song.albumName, restoredSong.albumName)
        assertEquals(song.isVideo, restoredSong.isVideo)
        assertEquals(song.isVideoOrigin, restoredSong.isVideoOrigin)
        assertEquals(song.playbackSource, restoredSong.playbackSource)
        assertEquals(song.playbackSourceId, restoredSong.playbackSourceId)
        assertEquals(DownloadQuality.LOSSLESS.name, restored.quality)
        assertEquals("avoid-1", restored.avoidSource)
        assertTrue(restored.forceReplace)
        assertTrue(restored.youtubeOnly)
        assertTrue(restored.automatic)
        assertTrue(restored.mvSoundRepair)
        assertTrue(restored.allowMetered)
        assertEquals(HqTier.LOSSLESS_16.name, restored.minimumTier)
        val restoredHq = PendingDownloadsStore.restoredHq(restored.hq!!)!!
        assertEquals(hq.sourceConfigId, restoredHq.sourceConfigId)
        assertEquals(hq.trackId, restoredHq.trackId)
        assertEquals(hq.tier, restoredHq.tier)
        assertEquals(hq.offsetMs, restoredHq.offsetMs)
        assertEquals(hq.rate, restoredHq.rate, 0.0)
    }

    @Test
    fun `a corrupt waiting queue is dropped instead of breaking startup`() {
        assertEquals(emptyList<PendingDownloadsStore.PendingEntry>(), PendingDownloadsStore.decode("{"))
        assertEquals(emptyList<PendingDownloadsStore.PendingEntry>(), PendingDownloadsStore.decode(null))
    }
}
