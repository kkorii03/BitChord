package com.music.bitchord.download

import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.ko.downloads.KoDownloadPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source that answers with HLS/DASH is audio delivered as an index, not an
 * unkeepable candidate. The v1.9 path keeps it so [KoDownloadPipeline] can join
 * its segments into one ordinary file; only a probe that cannot read a file at
 * all sends the download to YouTube.
 */
class DownloadsManifestKeepTest {

    private val stream = SourceStream(url = "https://cdn.example/dash/track", format = StreamFormat(codec = "flac"))
    private val flac = DownloadStore.Storable("flac", "audio/flac")

    @Test
    fun `a manifest candidate is kept for the assembler`() {
        assertNotNull(Downloads.keptFor(stream, flac, Downloader.Probe.Manifest(dash = true)))
        assertNotNull(Downloads.keptFor(stream, flac, Downloader.Probe.Manifest(dash = false)))
    }

    @Test
    fun `an unreadable candidate is still declined`() {
        assertNull(Downloads.keptFor(stream, flac, null))
    }

    @Test
    fun `an ordinary file candidate is kept`() {
        assertNotNull(
            Downloads.keptFor(
                stream,
                flac,
                Downloader.Probe.File(Downloader.Container.FLAC, totalBytes = 1_000L),
            ),
        )
    }

    /** The probe, not the extension, decides: an extensionless DASH stream is still a manifest. */
    @Test
    fun `an extensionless manifest is packaged from the probe`() {
        val dash = KoDownloadPipeline.manifestShape(stream.url, Downloader.Probe.Manifest(dash = true))
        assertTrue(dash.packaged)
        assertTrue(dash.dash)

        val hls = KoDownloadPipeline.manifestShape(stream.url, Downloader.Probe.Manifest(dash = false))
        assertTrue(hls.packaged)
        assertFalse(hls.dash)
    }

    /** Without a probe, the old URL rules still apply. */
    @Test
    fun `url rules still catch mpd and m3u8 without a probe`() {
        assertTrue(KoDownloadPipeline.manifestShape("https://cdn.example/x.mpd?token=1", null).dash)
        assertTrue(KoDownloadPipeline.manifestShape("https://cdn.example/x.m3u8?token=1", null).packaged)
        assertFalse(KoDownloadPipeline.manifestShape("https://cdn.example/x.flac?token=1", null).packaged)
    }

    @Test
    fun `a probed ordinary file is not packaged`() {
        val shape = KoDownloadPipeline.manifestShape(
            stream.url,
            Downloader.Probe.File(Downloader.Container.FLAC, totalBytes = 1_000L),
        )
        assertEquals(false, shape.packaged)
        assertEquals(false, shape.dash)
    }
}
