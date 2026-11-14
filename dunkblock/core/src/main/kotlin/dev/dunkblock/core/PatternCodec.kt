package dev.dunkblock.core

import java.nio.ByteBuffer

/**
 * On-screen test pattern used by the LATENCY test. The test activity draws a row of 20 blocks near the
 * top of the screen that encodes a 16-bit frame counter; the capture side decodes it from every captured
 * frame. This gives the REAL count of displayed / captured / duplicated / missed frames, and ties each
 * captured frame to the exact display frame it shows.
 *
 *   block 0 = white (marker), block 1 = black (marker), blocks 2..17 = counter MSB..LSB (white = 1),
 *   block 18 = even-parity bit over blocks 2..17, block 19 = white (end marker).
 */
object PatternCodec {
    const val BLOCKS = 20
    const val STRIP_Y = 0.12f           // strip centre, fraction of frame height
    const val STRIP_H = 0.05f           // strip height, fraction of frame height
    const val FLIP_X = 0.5f             // luma probe for the colour-flip box
    const val FLIP_Y = 0.55f
    const val LUMA_THRESHOLD = 128

    fun encode(counter: Int): BooleanArray {
        val bits = BooleanArray(BLOCKS)
        bits[0] = true; bits[1] = false; bits[19] = true
        var ones = 0
        for (i in 0 until 16) {
            val bit = (counter shr (15 - i)) and 1 == 1
            bits[2 + i] = bit
            if (bit) ones++
        }
        bits[18] = ones % 2 == 1   // makes the number of ones in blocks 2..18 even
        return bits
    }

    /** Luma (0..255) averaged over a 3x3 neighbourhood at normalized position (nx, ny). */
    fun lumaAt(src: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int, nx: Float, ny: Float): Int {
        val cx = (nx * w).toInt().coerceIn(1, w - 2)
        val cy = (ny * h).toInt().coerceIn(1, h - 2)
        var sum = 0
        for (dy in -1..1) for (dx in -1..1) {
            val p = (cy + dy) * rowStride + (cx + dx) * pixelStride
            val r = src.get(p).toInt() and 0xFF
            val g = src.get(p + 1).toInt() and 0xFF
            val b = src.get(p + 2).toInt() and 0xFF
            sum += (r * 77 + g * 151 + b * 28) shr 8
        }
        return sum / 9
    }

    /** Returns the 16-bit counter, or -1 if the markers / parity do not check out. */
    fun decode(src: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int): Int {
        if (w < 64 || h < 64) return -1
        val bits = BooleanArray(BLOCKS)
        for (i in 0 until BLOCKS) {
            val nx = (i + 0.5f) / BLOCKS
            bits[i] = lumaAt(src, w, h, rowStride, pixelStride, nx, STRIP_Y) >= LUMA_THRESHOLD
        }
        if (!bits[0] || bits[1] || !bits[19]) return -1
        var ones = 0
        var v = 0
        for (i in 0 until 16) { v = (v shl 1) or (if (bits[2 + i]) 1 else 0); if (bits[2 + i]) ones++ }
        if (bits[18]) ones++
        return if (ones % 2 == 0) v else -1
    }

    fun flipLuma(src: ByteBuffer, w: Int, h: Int, rowStride: Int, pixelStride: Int): Int =
        lumaAt(src, w, h, rowStride, pixelStride, FLIP_X, FLIP_Y)
}
