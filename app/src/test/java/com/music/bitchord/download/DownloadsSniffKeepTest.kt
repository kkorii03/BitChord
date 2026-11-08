package com.music.bitchord.download

import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source file is filed by what its opening bytes are, not by what the
 * source called it (upstream v1.9.1 tests, re-ported with the fork's
 * label-vs-bytes guard).
 */
class DownloadsSniffKeepTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun sniffed(head: ByteArray, contentType: String = "", stated: String? = null) =
        Downloader.sniff(head, contentType, stated, totalBytes = null)

    private fun containerOf(head: ByteArray, stated: String? = null) =
        (sniffed(head, stated = stated) as? Downloader.Probe.File)?.container

    @Test
    fun `a source file is filed by what its opening bytes are`() {
        assertEquals(Downloader.Container.FLAC, containerOf("fLaC\u0000\u0000".toByteArray()))
        assertEquals(Downloader.Container.MP4, containerOf("\u0000\u0000\u0000 ftypM4A ".toByteArray()))
        assertEquals(Downloader.Container.WAV, containerOf("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray()))
        assertEquals(Downloader.Container.OGG, containerOf("OggS\u0000\u0002".toByteArray()))
        assertEquals(Downloader.Container.WEBM, containerOf(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01)))
        assertEquals(Downloader.Container.ADTS, containerOf(bytes(0xFF, 0xF1, 0x50, 0x80)))
        assertEquals(Downloader.Container.MP3, containerOf(bytes(0xFF, 0xFB, 0x90, 0x64)))
        assertEquals(Downloader.Container.MP3, containerOf("ID3\u0004\u0000".toByteArray()))
        assertEquals(Downloader.Container.ADTS, containerOf("ID3\u0004\u0000".toByteArray(), stated = "aac"))
        assertEquals("m4a", Downloader.Container.MP4.extension)
        assertEquals("audio/mpeg", Downloader.Container.MP3.mimeType)
    }

    @Test
    fun `a manifest is recognised whatever its URL or label says`() {
        assertEquals(
            Downloader.Probe.Manifest(dash = false),
            sniffed("#EXTM3U\n#EXT-X-VERSION:7\n".toByteArray(), stated = "flac"),
        )
        assertEquals(Downloader.Probe.Manifest(dash = false), sniffed("﻿\n#EXTM3U\n".toByteArray()))
        assertEquals(
            Downloader.Probe.Manifest(dash = true),
            sniffed("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<MPD xmlns=".toByteArray()),
        )
        assertEquals(
            Downloader.Probe.Manifest(dash = false),
            sniffed("garbage".toByteArray(), contentType = "application/vnd.apple.mpegurl"),
        )
    }

    @Test
    fun `bytes that are no audio container are not kept`() {
        assertNull(sniffed("<html><body>Not found".toByteArray()))
        assertNull(sniffed(ByteArray(0)))
        // AIFF has no extractor in the player, so a saved one could never play.
        assertNull(sniffed("FORM\u0000\u0000\u0000\u0000AIFFCOMM".toByteArray()))
    }

    @Test
    fun `unstated bitrate is measured from the file's size`() {
        // 9.6 MB over four minutes is 320 kbps.
        assertEquals(320, Downloads.estimatedKbps(9_600_000, 240))
        assertNull(Downloads.estimatedKbps(null, 240))
        assertNull(Downloads.estimatedKbps(9_600_000, null))
        assertNull(Downloads.estimatedKbps(9_600_000, 0))
    }

    @Test
    fun `an unlabelled flac is kept and named from its bytes`() {
        val stream = SourceStream(url = "https://cdn.example/x", format = StreamFormat())
        val kept = Downloads.keptFor(
            stream,
            DownloadStore.storable(stream.format.codec),
            Downloader.Probe.File(Downloader.Container.FLAC, totalBytes = 30_000_000L),
            durationSec = 240,
        )
        assertNotNull(kept)
        assertEquals("flac", kept!!.storable.extension)
        assertEquals("flac", kept.stream.format.codec)
        assertEquals(true, kept.stream.format.isLossless)
    }

    @Test
    fun `an unlabelled mp3 is kept with a measured bitrate`() {
        val stream = SourceStream(url = "https://cdn.example/x", format = StreamFormat(codec = "mp3"))
        val kept = Downloads.keptFor(
            stream,
            DownloadStore.storable(stream.format.codec),
            Downloader.Probe.File(Downloader.Container.MP3, totalBytes = 9_600_000L),
            durationSec = 240,
        )
        assertNotNull(kept)
        assertEquals("mp3", kept!!.storable.extension)
        assertEquals(320, kept.stream.format.kbps)
        assertTrue(kept.stream.format.kbpsInferred)
    }

    @Test
    fun `a stated bitrate is not overwritten by the estimate`() {
        val stream = SourceStream(url = "https://cdn.example/x", format = StreamFormat(codec = "mp3", kbps = 256))
        val kept = Downloads.keptFor(
            stream,
            null,
            Downloader.Probe.File(Downloader.Container.MP3, totalBytes = 9_600_000L),
            durationSec = 240,
        )
        assertEquals(256, kept!!.stream.format.kbps)
    }

    @Test
    fun `a lossy label whose bytes are flac is filed as flac`() {
        val stream = SourceStream(url = "https://cdn.example/x", format = StreamFormat(codec = "mp3"))
        val kept = Downloads.keptFor(
            stream,
            null,
            Downloader.Probe.File(Downloader.Container.FLAC, totalBytes = 30_000_000L),
            durationSec = 240,
        )
        assertEquals("flac", kept!!.storable.extension)
    }

    @Test
    fun `a lossless label that disagrees with the bytes is still declined`() {
        val stream = SourceStream(url = "https://cdn.example/x", format = StreamFormat(codec = "flac"))
        assertNull(
            Downloads.keptFor(
                stream,
                DownloadStore.storable("flac"),
                Downloader.Probe.File(Downloader.Container.MP3, totalBytes = 9_600_000L),
            ),
        )
    }

    @Test
    fun `a manifest with no codec to file it as is declined`() {
        val stream = SourceStream(url = "https://cdn.example/x", format = StreamFormat(codec = "mp3"))
        assertNull(Downloads.keptFor(stream, null, Downloader.Probe.Manifest(dash = false)))
    }
}
