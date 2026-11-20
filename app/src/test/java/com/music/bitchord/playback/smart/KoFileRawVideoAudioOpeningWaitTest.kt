package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [KO] x1120-nextdl: a downloaded WebM file is sent to the speech server as its own
 * bytes, the way the cached stream is, instead of a whole-file decode to WAV
 * (11-20 Un7GffA7zHc: 10.4 s of decode for 8.5 MB of WAV off a 271 s file).
 */
class KoFileRawVideoAudioOpeningWaitTest {

    private val webmHead = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val mp4Head = byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70)

    private fun file(size: Int, head: ByteArray): ByteArray = ByteArray(size).also { head.copyInto(it) }

    private fun open(bytes: ByteArray, durationMs: Long, untilSeconds: Double): VideoAudio.SongAudio? =
        VideoAudio.fileOpening(bytes.size.toLong(), durationMs, untilSeconds, null, null) { n ->
            bytes.copyOf(minOf(n, bytes.size))
        }

    @Test
    fun `a whole downloaded webm under the window goes as it is`() {
        // 4.0 MB, 240 s: the 420 s AI ask takes all of it.
        val audio = open(file(4_000_000, webmHead), durationMs = 240_000L, untilSeconds = 420.0)
        assertNotNull(audio)
        audio!!
        assertEquals("audio/webm", audio.mime)
        assertEquals(4_000_000, audio.bytes.size)
        assertTrue(audio.fromFile)
        assertTrue(audio.whole)
    }

    @Test
    fun `a song check's ninety seconds is cut from the file`() {
        // 8.47 MB, 271 s: 91 s of it.
        val audio = open(file(8_470_000, webmHead), durationMs = 271_000L, untilSeconds = 90.0)!!
        assertEquals(8_470_000.0 * 91.0 / 271.0, audio.bytes.size.toDouble(), 2.0)
        assertFalse(audio.whole)
        assertEquals(91.0, audio.roughSeconds!!, 0.5)
    }

    @Test
    fun `a file of unknown length in time uses the stream's byte rate`() {
        val audio = open(file(8_000_000, webmHead), durationMs = 0L, untilSeconds = 90.0)!!
        assertEquals(1_800_000, audio.bytes.size)
    }

    @Test
    fun `mp4 and unknown files are left to the decoder`() {
        assertNull(open(file(4_000_000, mp4Head), durationMs = 240_000L, untilSeconds = 420.0))
        assertNull(open(file(4_000_000, byteArrayOf(1, 2, 3, 4)), durationMs = 240_000L, untilSeconds = 420.0))
        assertNull(VideoAudio.rawFileTake("audio/flac", 4_000_000L, 4_000_000L))
        assertNull(VideoAudio.rawFileTake(null, 4_000_000L, 4_000_000L))
    }

    @Test
    fun `too little or too much is left to the decoder`() {
        assertNull(VideoAudio.rawFileTake("audio/webm", 100_000L, 100_000L))
        assertNull(VideoAudio.rawFileTake("audio/ogg", 40_000_000L, 40_000_000L))
        assertEquals(3_000_000L, VideoAudio.rawFileTake("audio/ogg", 40_000_000L, 3_000_000L))
        assertNull(open(ByteArray(0), durationMs = 240_000L, untilSeconds = 90.0))
    }

    @Test
    fun `a local file no longer skips the raw path`() {
        val opening = File(".").canonicalFile.let { root ->
            listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/playback/smart/VideoAudio.kt") }
                .first { it.exists() }.readText()
        }.substringAfter("internal suspend fun songStreamOpening(").substringBefore("val videoId = song.videoId")
        assertTrue("return@withContext fileOpening(song, local, untilSeconds)" in opening)
    }
}
