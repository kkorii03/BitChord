package com.music.bitchord.download

import com.music.bitchord.data.settings.AppSettings
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

    /**
     * The bytes, not the source label, decide the extension. AAC in an MP4
     * served as FLAC used to win the source race and be saved as `.flac`, so
     * only the later sound check could condemn it.
     */
    @Test
    fun `a file whose real container conflicts with its name is declined`() {
        assertNull(
            Downloads.keptFor(
                stream,
                flac,
                Downloader.Probe.File(Downloader.Container.MP4, totalBytes = 1_000L),
            ),
        )
        assertNull(
            Downloads.keptFor(
                stream,
                flac,
                Downloader.Probe.File(Downloader.Container.ADTS, totalBytes = 1_000L),
            ),
        )
        assertNotNull(
            Downloads.keptFor(
                stream,
                DownloadStore.Storable("m4a", "audio/mp4"),
                Downloader.Probe.File(Downloader.Container.MP4, totalBytes = 1_000L),
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

    /** A file served from a manifest-looking URL is still a file; the probe wins. */
    @Test
    fun `a file probe beats a manifest-looking url`() {
        val file = Downloader.Probe.File(Downloader.Container.FLAC, totalBytes = 1_000L)
        listOf("https://cdn.example/x.mpd?token=1", "https://cdn.example/x.m3u8?token=1").forEach { url ->
            val shape = KoDownloadPipeline.manifestShape(url, file)
            assertEquals("$url must not be packaged", false, shape.packaged)
            assertEquals("$url must not be DASH", false, shape.dash)
        }
    }

    /**
     * An export can only take a manifest the assembler can file. Declining it
     * here lets the resolver keep looking instead of locking onto this source
     * and then falling all the way to YouTube.
     */
    @Test
    fun `an exported manifest the assembler cannot file is declined`() {
        val webm = DownloadStore.Storable("webm", "audio/webm")
        try {
            AppSettings.exportDownloads.value = true
            assertNull(Downloads.keptFor(stream, webm, Downloader.Probe.Manifest(dash = true)))
            // A manifest the assembler can file is still kept while exporting.
            assertNotNull(Downloads.keptFor(stream, flac, Downloader.Probe.Manifest(dash = false)))
        } finally {
            AppSettings.exportDownloads.value = false
        }
        assertNotNull(Downloads.keptFor(stream, webm, Downloader.Probe.Manifest(dash = true)))
    }
}
