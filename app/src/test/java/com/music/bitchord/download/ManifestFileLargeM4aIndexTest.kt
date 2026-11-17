package com.music.bitchord.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile

/**
 * Review D7: an m4a manifest (hi-res ALAC / Atmos) is assembled as a fragmented
 * MP4 with no `sidx`. The only step that added one ran inside the tagger, which
 * skips files over its 32 MB in-memory cap, so a large track was saved
 * unseekable. The assembly now writes the index itself, at any size, while
 * holding one segment at a time.
 */
class ManifestFileLargeM4aIndexTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun u32(v: Long) = ByteArray(4) { (v shr (8 * (3 - it))).toByte() }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().also { o -> parts.forEach(o::write) }.toByteArray()
        return u32(8L + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private val init: ByteArray = run {
        val stsd = box("stsd", u32(0), u32(1), box("alac", ByteArray(28)))
        val mdhd = box("mdhd", u32(0), u32(0), u32(0), u32(44_100), u32(0), u32(0))
        val tkhd = box("tkhd", u32(0), u32(0), u32(0), u32(1), u32(0), u32(0))
        val trak = box("trak", tkhd, box("mdia", mdhd, box("minf", box("stbl", stsd))))
        val trex = box("trex", u32(0), u32(1), u32(1), u32(0), u32(0), u32(0))
        box("ftyp", "iso8".toByteArray()) + box("moov", trak, box("mvex", trex))
    }

    /** One CMAF segment holding a single [frameBytes]-long frame of [ticks]. */
    private fun segment(frameBytes: Int, ticks: Long, decodeTime: Long): ByteArray {
        val tfhd = box("tfhd", u32(0x20000), u32(1))
        val tfdt = box("tfdt", u32(0), u32(decodeTime))
        fun moof(offset: Long) = box(
            "moof",
            box("traf", tfhd, tfdt, box("trun", u32(0x301), u32(1), u32(offset), u32(ticks), u32(frameBytes.toLong()))),
        )
        val moofSize = moof(0).size
        val mdat = box("mdat", ByteArray(frameBytes) { it.toByte() })
        return box("styp", "msdh".toByteArray()) + box("sidx", ByteArray(32)) + moof(moofSize + 8L) + mdat
    }

    @Test
    fun `an m4a over the tagger's memory cap is still indexed`() {
        val frame = 17 * 1024 * 1024
        val ticks = 44_100L * 60
        // Built lazily, as the real fetch is: one segment in memory at a time.
        val media = sequenceOf(0L, ticks).map { decode -> segment(frame, ticks, decode) }
        val file = tmp.newFile("big.m4a")
        val work = tmp.newFolder("work")
        file.outputStream().buffered().use { sink ->
            ManifestFile.assemble(work, "m4a", init, media, sink)
        }

        val length = file.length()
        assertFalse("the tagger would skip this file", MediaTagger.canTagInMemory(length))

        val head = ByteArray(init.size + 64 + 256)
        RandomAccessFile(file, "r").use { it.readFully(head) }
        val top = Mp4Boxes.boxes(head).map { it.type }
        // Old assembly: ftyp, moov, moof, … with no index anywhere.
        assertEquals(listOf("ftyp", "moov", "sidx"), top.take(3))

        val sidx = Mp4Boxes.boxes(head)[2]
        assertEquals(40 + 12 * 2, sidx.size)
        assertEquals(2, ((head[sidx.offset + 38].toInt() and 0xFF) shl 8) or (head[sidx.offset + 39].toInt() and 0xFF))
        val first = Mp4Boxes.readU32(head, sidx.offset + 40)
        val second = Mp4Boxes.readU32(head, sidx.offset + 52)
        assertEquals("both fragments reach the end of the file", length - sidx.end, first + second)
        assertEquals(ticks, Mp4Boxes.readU32(head, sidx.offset + 44))
        assertTrue(work.listFiles().isNullOrEmpty()) // temporary fragments file cleaned up
    }

    @Test
    fun `an init the index cannot describe keeps the plain assembly`() {
        // Two tracks: Mp4Sidx refuses these, and so does the assembly.
        val tkhd = box("tkhd", u32(0), u32(0), u32(0), u32(1), u32(0), u32(0))
        val trak = box("trak", tkhd)
        val twoTracks = box("ftyp", "iso8".toByteArray()) + box("moov", trak, trak, box("mvex"))
        val out = ByteArrayOutputStream()
        ManifestFile.assemble(tmp.root, "m4a", twoTracks, sequenceOf(segment(64, 1024, 0)), out)
        assertEquals(listOf("ftyp", "moov", "moof", "mdat"), Mp4Boxes.boxes(out.toByteArray()).map { it.type })
    }
}
