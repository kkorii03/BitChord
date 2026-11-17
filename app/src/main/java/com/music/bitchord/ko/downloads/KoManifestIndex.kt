package com.music.bitchord.ko.downloads

import com.music.bitchord.download.Mp4Boxes
import java.io.File
import java.io.OutputStream

/**
 * Gives a manifest assembled as `.m4a` its `sidx` while it is being written.
 *
 * Review D7. The assembly is a fragmented MP4 with no index, and the only step
 * that added one was [com.music.bitchord.download.Mp4Sidx.ensure] inside
 * `MediaTagger.embedNow`, which needs the whole file in memory (about three
 * copies) and is skipped above the tagger's 32 MB OOM cap. A hi-res ALAC or
 * Atmos track is often bigger than that, so it was saved unseekable: it plays
 * from the start, and every seek (scrubber, lyric-line tap) is ignored.
 *
 * Here the fragments go to a temporary file one segment at a time, as the FLAC
 * assembly already does, while each `moof`'s duration is summed; then the init,
 * the index and the fragments are written. The result is byte for byte what
 * `Mp4Sidx.ensure` makes of the plain assembly, and the tagger's own
 * `ensure` then finds the index and adds nothing. Memory stays at one segment
 * whatever the file size; the cost is one extra disk pass of the track.
 */
internal object KoManifestIndex {

    private class Track(
        val moovEnd: Int,
        val trackId: Long,
        val timescale: Long,
        val trexDuration: Long,
        val trexSize: Long,
    )

    /** A `moof`'s position in the plain assembly, its length in ticks and its first decode time. */
    private class Fragment(val offset: Long, val ticks: Long, val decodeTime: Long?)

    /**
     * Writes [init] and [media] to [sink] as one indexed fragmented MP4.
     *
     * Returns false without consuming [media] when [init] alone rules an index
     * out (the same refusals as `Mp4Sidx`: no `moov`/`mvex`, not exactly one
     * track, an index already present); the caller then writes the plain
     * assembly. Once it has started it always finishes: if a fragment turns
     * out unreadable the file is written as before, without an index.
     */
    fun writeIndexed(cacheDir: File, init: ByteArray, media: Sequence<ByteArray>, sink: OutputStream): Boolean {
        val track = runCatching { trackOf(init) }.getOrNull() ?: return false
        val body = File.createTempFile("manifest-", ".m4a", cacheDir)
        try {
            val fragments = mutableListOf<Fragment>()
            var indexable = true
            var position = init.size.toLong()
            body.outputStream().buffered().use { out ->
                media.forEach { segment ->
                    // The same boxes ManifestFile writes: a segment's own
                    // styp/sidx describes only that segment.
                    Mp4Boxes.boxes(segment).filter { it.type != "styp" && it.type != "sidx" }.forEach { box ->
                        if (indexable && box.type == "moof") {
                            val fragment = runCatching { fragmentOf(segment, box, track, position) }.getOrNull()
                            if (fragment == null) indexable = false else fragments += fragment
                        }
                        out.write(segment, box.offset, box.size)
                        position += box.size
                    }
                }
            }
            val sidx = if (indexable) sidxFor(track, fragments, total = position) else null
            if (sidx != null) {
                sink.write(init, 0, track.moovEnd)
                sink.write(sidx)
                sink.write(init, track.moovEnd, init.size - track.moovEnd)
            } else {
                sink.write(init)
            }
            body.inputStream().use { it.copyTo(sink) }
        } finally {
            body.delete()
        }
        return true
    }

    private fun trackOf(init: ByteArray): Track? {
        val top = Mp4Boxes.boxes(init)
        if (top.any { it.type == "sidx" || it.type == "moof" }) return null
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val children = Mp4Boxes.boxes(init, moov.content, moov.end)
        val mvex = children.firstOrNull { it.type == "mvex" } ?: return null
        val traks = children.filter { it.type == "trak" }
        if (traks.size != 1) return null
        val trak = traks.first()
        val mdhd = Mp4Boxes.child(init, Mp4Boxes.child(init, trak, "mdia") ?: return null, "mdhd") ?: return null
        val timescale = Mp4Boxes.readU32(init, mdhd.content + if (init[mdhd.content].toInt() == 1) 20 else 12)
        if (timescale <= 0) return null
        val tkhd = Mp4Boxes.child(init, trak, "tkhd") ?: return null
        val trackId = Mp4Boxes.readU32(init, tkhd.content + if (init[tkhd.content].toInt() == 1) 20 else 12)
        val trex = Mp4Boxes.boxes(init, mvex.content, mvex.end)
            .firstOrNull { it.type == "trex" && Mp4Boxes.readU32(init, it.content + 4) == trackId }
        return Track(
            moovEnd = moov.end,
            trackId = trackId,
            timescale = timescale,
            trexDuration = trex?.let { Mp4Boxes.readU32(init, it.content + 12) } ?: 0L,
            trexSize = trex?.let { Mp4Boxes.readU32(init, it.content + 16) } ?: 0L,
        )
    }

    private fun fragmentOf(segment: ByteArray, moof: Mp4Boxes.Box, track: Track, position: Long): Fragment? {
        val samples = Mp4Boxes.samples(segment, moof, track.trackId, track.trexDuration, track.trexSize) ?: return null
        val traf = Mp4Boxes.child(segment, moof, "traf")
        val tfdt = traf?.let { Mp4Boxes.child(segment, it, "tfdt") }
        val decodeTime = tfdt?.let {
            if (segment[it.content].toInt() == 1) {
                Mp4Boxes.readU64(segment, it.content + 4)
            } else {
                Mp4Boxes.readU32(segment, it.content + 4)
            }
        }
        return Fragment(position, samples.sumOf { it.duration }, decodeTime)
    }

    /** The `sidx` `Mp4Sidx` builds, from fragments measured in the plain assembly. */
    private fun sidxFor(track: Track, fragments: List<Fragment>, total: Long): ByteArray? {
        // reference_count is 16 bits and referenced_size 31.
        if (fragments.isEmpty() || fragments.size > 0xFFFF) return null
        val sizes = fragments.mapIndexed { i, f -> (fragments.getOrNull(i + 1)?.offset ?: total) - f.offset }
        if (fragments.any { it.ticks <= 0 } || sizes.any { it <= 0 || it > 0x7FFFFFFFL }) return null
        val sidxSize = 40 + 12 * fragments.size
        val out = ByteArray(sidxSize)
        Mp4Boxes.writeU32(out, 0, sidxSize.toLong())
        "sidx".forEachIndexed { i, c -> out[4 + i] = c.code.toByte() }
        out[8] = 1 // version 1: 64-bit times and offset
        Mp4Boxes.writeU32(out, 12, track.trackId)
        Mp4Boxes.writeU32(out, 16, track.timescale)
        Mp4Boxes.writeU64(out, 20, fragments.first().decodeTime ?: 0L)
        // Measured from the end of the sidx, which sits where moov ended.
        Mp4Boxes.writeU64(out, 28, fragments.first().offset - track.moovEnd)
        out[38] = (fragments.size shr 8).toByte()
        out[39] = fragments.size.toByte()
        fragments.forEachIndexed { i, f ->
            val at = 40 + 12 * i
            Mp4Boxes.writeU32(out, at, sizes[i])
            Mp4Boxes.writeU32(out, at + 4, f.ticks)
            // starts_with_SAP, SAP type 1: every audio frame is a sync sample.
            Mp4Boxes.writeU32(out, at + 8, 0x90000000L)
        }
        return out
    }
}
