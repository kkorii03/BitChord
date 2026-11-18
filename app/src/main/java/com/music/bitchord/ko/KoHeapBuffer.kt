package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog

/**
 * [KO] oom3: the audition player's read-ahead, sized by the Java heap left when
 * it is built.
 *
 * Media3 keeps every buffered byte in 64 KiB arrays on the Java heap, and the
 * audition asks for up to PlaybackService's AUDITION_BUFFER_BYTES (24 MiB) on
 * top of the two players' own buffers. On the 256 MB phone heap that is what
 * an InnerTubeX cipher solve (~40-55 MB of transient strings) lands on; the
 * 11-19 OOMs hit at 250/256 MB. So the audition takes at most a quarter of the
 * headroom, never less than [AUDITION_FLOOR_BYTES]: 8 MiB still covers the
 * 12 s prebuffer plus drift for 16-bit FLAC and 24/48 FLAC; only a 24/96 copy
 * on a nearly full heap may time out its proof, and that keeps the old stream
 * playing (an upgrade not made), never a crash.
 *
 * Read once per audition (each is a new player); no timer, no polling.
 */
object KoHeapBuffer {
    /** The smallest audition buffer: the main player's Wi-Fi ceiling. */
    const val AUDITION_FLOOR_BYTES = 8 * 1024 * 1024

    /** The audition may take this fraction (1/n) of the free heap. */
    private const val HEADROOM_SHARE = 4

    /** [wanted] capped at a quarter of the heap still free, never under [floor] (or [wanted] if smaller). */
    fun bytes(wanted: Int, floor: Int, maxHeap: Long, usedHeap: Long): Int {
        val low = minOf(floor, wanted).toLong()
        val share = (maxHeap - usedHeap).coerceAtLeast(0L) / HEADROOM_SHARE
        return share.coerceIn(low, wanted.toLong()).toInt()
    }

    /** The audition's target bytes for the heap as it is now; logs only when it is reduced. */
    fun auditionBytes(wanted: Int): Int {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory()
        val used = rt.totalMemory() - rt.freeMemory()
        val bytes = bytes(wanted, AUDITION_FLOOR_BYTES, max, used)
        if (bytes < wanted) {
            TrackLog.d(TAG, "audition buffer: ${bytes / MIB} MiB of ${wanted / MIB} (heap ${used / MIB}/${max / MIB} MB)")
        }
        return bytes
    }

    private const val MIB = 1024 * 1024
    private const val TAG = "BitChord"
}
