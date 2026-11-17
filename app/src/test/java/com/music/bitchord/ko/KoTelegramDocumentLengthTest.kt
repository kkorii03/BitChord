package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.TelegramSource
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.telegram.TelegramTrack
import com.music.bitchord.telegram.TelegramTrackMapper
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review 11-17, C3a-1. A FLAC posted to a Telegram chat as a document has no
 * runtime in TDLib, so its row had no `durationText` and every strict-length
 * walk refused it as `duration`: the lossless copy could never be swapped in.
 * The strict check stays; a matching document row is measured from its own
 * STREAMINFO first.
 */
class KoTelegramDocumentLengthTest {

    private val config = "tg"
    private val target = TrackMatcher.Target("Blue Night", "Kim Artist", 200)

    @Before
    fun clear() = KoTelegramDocumentLength.clearForTest()

    @After
    fun clearAfter() = KoTelegramDocumentLength.clearForTest()

    private fun document(
        messageId: Long = 2,
        fileName: String = "Kim Artist - Blue Night.flac",
        title: String = "Blue Night",
        artist: String = "Kim Artist",
        mime: String = "audio/flac",
    ): Song = TelegramTrackMapper.toSong(
        TelegramTrack(
            id = "1_$messageId",
            chatId = 1,
            messageId = messageId,
            fileId = 3,
            size = 30_000_000,
            title = title,
            artist = artist,
            durationSec = 0,
            format = fileName.substringAfterLast('.'),
            mimeType = mime,
            fileName = fileName,
        ),
        config,
    )

    /** A FLAC opening whose STREAMINFO says [seconds] at [rate], as StreamContainerSniffTest builds it. */
    private fun flacHead(rate: Int, seconds: Int): ByteArray {
        val samples = rate.toLong() * seconds
        val info = ByteArray(34)
        info[10] = (rate shr 12).toByte()
        info[11] = (rate shr 4).toByte()
        info[12] = (((rate and 0x0F) shl 4) or (1 shl 1)).toByte()
        info[13] = ((15 shl 4) or ((samples shr 32).toInt() and 0x0F)).toByte()
        info[14] = (samples shr 24).toByte()
        info[15] = (samples shr 16).toByte()
        info[16] = (samples shr 8).toByte()
        info[17] = samples.toByte()
        return "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x00, 0x00, 0x22) + info
    }

    private fun strictReason(song: Song) =
        TrackMatcher.rejectionReason(song, target, strictLength = true)

    @Test
    fun `a document row is refused for duration today and matches once its head is read`() {
        val row = document()
        assertNull(row.durationText)
        assertEquals("duration", strictReason(row))

        val measured = KoTelegramDocumentLength.withHead(row, flacHead(44_100, 200))
        assertEquals("3:20", measured.durationText)
        assertEquals("match", strictReason(measured))
        assertTrue(TrackMatcher.withinSeconds(measured, target, 2))
    }

    @Test
    fun `a head that is not a FLAC leaves the row refused`() {
        val ogg = "OggS".toByteArray(Charsets.US_ASCII) + ByteArray(60)
        val row = KoTelegramDocumentLength.withHead(document(), ogg)
        assertNull(row.durationText)
        assertEquals("duration", strictReason(row))
    }

    @Test
    fun `only a lossless row that matches by title and artist is worth a read`() {
        assertTrue(KoTelegramDocumentLength.needsLength(document(), target))
        assertFalse(
            KoTelegramDocumentLength.needsLength(
                document(fileName = "Kim Artist - Blue Night.mp3", mime = "audio/mpeg"),
                target,
            ),
        )
        assertFalse(KoTelegramDocumentLength.needsLength(document(title = "Red Morning"), target))
        assertFalse(KoTelegramDocumentLength.needsLength(document(artist = "Someone Else"), target))
    }

    @Test
    fun `a strict walk measures once and remembers the length`() = runBlocking {
        val reads = mutableListOf<String>()
        val reader: suspend (String) -> ByteArray? = { id -> reads += id; flacHead(44_100, 200) }

        val first = KoTelegramDocumentLength.measureRows(config, listOf(document()), target, true, reader)
        assertEquals("3:20", first.single().durationText)
        assertEquals("match", strictReason(first.single()))
        assertEquals(listOf("1_2"), reads)

        // The next walk (or a memoised answer) is filled without another read.
        val again = KoTelegramDocumentLength.measureRows(config, listOf(document()), target, true, reader)
        assertEquals("3:20", again.single().durationText)
        assertEquals(1, reads.size)
        assertEquals("3:20", KoTelegramDocumentLength.known(telegramSource, listOf(document())).single().durationText)
        assertEquals(200, KoTelegramDocumentLength.seconds("1_2"))
    }

    @Test
    fun `no read outside a strict walk, for another source, or for a non-FLAC already seen`() = runBlocking {
        var reads = 0
        val ogg: suspend (String) -> ByteArray? = { reads++; "OggS".toByteArray(Charsets.US_ASCII) }

        val loose = KoTelegramDocumentLength.measureRows(config, listOf(document()), target, false, ogg)
        assertNull(loose.single().durationText)
        val other = KoTelegramDocumentLength.measureRows(null, listOf(document()), target, true, ogg)
        assertNull(other.single().durationText)
        assertEquals(0, reads)

        KoTelegramDocumentLength.measureRows(config, listOf(document()), target, true, ogg)
        KoTelegramDocumentLength.measureRows(config, listOf(document()), target, true, ogg)
        assertEquals("a head that is not a FLAC is read once", 1, reads)
        assertNull(KoTelegramDocumentLength.seconds("1_2"))
    }

    @Test
    fun `a failed read keeps nothing and is tried again later`() = runBlocking {
        var reads = 0
        val failing: suspend (String) -> ByteArray? = { reads++; throw IOException("gone") }
        val row = KoTelegramDocumentLength.measureRows(config, listOf(document()), target, true, failing)
        assertNull(row.single().durationText)
        assertEquals("duration", strictReason(row.single()))
        KoTelegramDocumentLength.measureRows(config, listOf(document()), target, true, failing)
        assertEquals(2, reads)
    }

    @Test
    fun `one answer reads at most two heads`() = runBlocking {
        var reads = 0
        val reader: suspend (String) -> ByteArray? = { reads++; flacHead(44_100, 200) }
        val rows = (2L..5L).map { document(messageId = it) }
        KoTelegramDocumentLength.measureRows(config, rows, target, true, reader)
        assertEquals(2, reads)
    }

    @Test
    fun `the resolver measures only in a strict walk and fills memoised answers`() {
        val root = File(".").canonicalFile
        val resolver = listOf("app/", "")
            .map { File(root, "${it}src/main/java/com/music/bitchord/data/sources/SourceResolver.kt") }
            .first { it.exists() }
            .readText()
        val walk = resolver.substringAfter("private suspend fun matchAndStream(")
            .substringBefore("val ranked = TrackMatcher.ranked(candidates, matchOn)")
        assertTrue(walk.contains("KoTelegramDocumentLength.known(source, cached)"))
        assertTrue(
            walk.contains(
                "KoTelegramDocumentLength.measure(source, rows, target, " +
                    "strictLength && (target.durationSec != null || lengthLater != null))",
            ),
        )
    }

    /** Only [KoTelegramDocumentLength.known]'s kind and id checks are used; nothing is called on it. */
    private val telegramSource = TelegramSource(SourceConfig(id = "tg", kind = SourceKind.TELEGRAM))
}
