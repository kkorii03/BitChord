package com.music.bitchord.ko.downloads

import com.music.bitchord.data.sources.StreamFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KO] HQ-DOWN, 11-18 16:31 Bones: the library replace must read both files'
 * own STREAMINFO before the old one goes, and a plain FLAC download must
 * record its measured rung instead of the badge's 16-bit.
 */
class KoFlacMeasureTest {

    /** "fLaC", a last-block STREAMINFO header, then the 34-byte payload. */
    private fun header(rate: Int, bits: Int, channels: Int = 2): ByteArray {
        val out = ByteArray(42)
        "fLaC".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        out[4] = 0x80.toByte()
        out[7] = 34
        val packed = (rate.toLong() shl 12) or ((channels - 1).toLong() shl 9) or ((bits - 1).toLong() shl 4)
        out[18] = (packed ushr 24).toByte()
        out[19] = (packed ushr 16).toByte()
        out[20] = (packed ushr 8).toByte()
        out[21] = packed.toByte()
        return out
    }

    @Test
    fun `STREAMINFO gives the real depth and rate`() {
        val hiRes = KoFlacMeasure.parse(header(48_000, 24))
        assertEquals(24, hiRes?.bitDepth)
        assertEquals(48_000, hiRes?.sampleRateHz)
        assertEquals(true, hiRes?.isLossless)
        val cd = KoFlacMeasure.parse(header(44_100, 16))
        assertEquals(16, cd?.bitDepth)
        assertEquals(44_100, cd?.sampleRateHz)
        assertEquals(96_000, KoFlacMeasure.parse(header(96_000, 24, channels = 6))?.sampleRateHz)
    }

    @Test
    fun `anything that is not a plain FLAC head is unknown`() {
        assertNull(KoFlacMeasure.parse(ByteArray(42)))
        assertNull(KoFlacMeasure.parse(header(48_000, 24).copyOf(20)))
        val id3 = header(48_000, 24).also { it[0] = 'I'.code.toByte(); it[1] = 'D'.code.toByte(); it[2] = '3'.code.toByte() }
        assertNull(KoFlacMeasure.parse(id3))
    }

    @Test
    fun `a file on disk is read from its first bytes`() {
        val file = File.createTempFile("ko-flac", ".flac")
        try {
            file.writeBytes(header(48_000, 24) + ByteArray(4096))
            assertEquals(24, KoFlacMeasure.read(file)?.bitDepth)
            file.writeBytes(ByteArray(10))
            assertNull(KoFlacMeasure.read(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a staged 16-bit file does not replace a 24-bit one`() {
        val old = KoFlacMeasure.parse(header(48_000, 24))
        val staged = KoFlacMeasure.parse(header(44_100, 16))
        assertEquals(KoFlacMeasure.KEPT_MESSAGE, KoFlacMeasure.refusal("Bones", old, staged, "FLAC"))
        // The thrown message is a permanent failure: the pair is not tried again.
        assertFalse(KoDownloadPipeline.automaticFailure(IllegalStateException(KoFlacMeasure.KEPT_MESSAGE)).transient)
    }

    @Test
    fun `an upgrade, an unknown old file or an unreadable lossless file passes`() {
        val cd = KoFlacMeasure.parse(header(44_100, 16))
        val hiRes = KoFlacMeasure.parse(header(96_000, 24))
        assertNull(KoFlacMeasure.refusal("a", cd, hiRes, "FLAC"))
        assertNull(KoFlacMeasure.refusal("a", null, cd, "FLAC"))
        // An ALAC/M4A replacement has no STREAMINFO: unknown, not a downgrade.
        assertNull(KoFlacMeasure.refusal("a", hiRes, null, "ALAC"))
        // A lossy badge is known to be lower even without a header.
        assertTrue(KoFlacMeasure.refusal("a", cd, null, "AAC 256") != null)
    }

    @Test
    fun `the transfer gates the staged bytes before the commit and records the measured rung`() {
        val root = File(".").canonicalFile
        fun source(path: String) = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }.readText()
        val transfer = source("download/Downloads.kt")
            .substringAfter("private suspend fun transfer(")
            .substringBefore("} catch (e: Throwable) {")
        val gate = transfer.indexOf("KoFlacMeasure.refusedLowerFile(context, setOf(song.videoId, track.videoId), destination.tagUri, route.downloadFormat, plan.hq)")
        assertTrue("gate missing", gate >= 0)
        assertTrue("gate must run before the commit", gate < transfer.indexOf("destination.commit()"))
        assertTrue(
            source("ko/downloads/KoDownloadPipeline.kt")
                .contains("hqTier = hq?.tier?.name ?: KoFlacMeasure.measuredTierName(uri, downloadFormat)"),
        )
    }

    @Test
    fun `a measured format reads as its hi-res rung`() {
        val measured = StreamFormat(codec = "flac", sampleRateHz = 48_000, bitDepth = 24)
        assertEquals(
            com.music.bitchord.data.sources.HqTier.HI_RES_24_44,
            com.music.bitchord.data.sources.HqQuality.tierOf(measured),
        )
    }
}
