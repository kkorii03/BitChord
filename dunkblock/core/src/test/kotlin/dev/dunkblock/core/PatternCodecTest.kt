package dev.dunkblock.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class PatternCodecTest {
    /** Renders the strip like the test activity would, into an RGBA buffer with row padding. */
    private fun render(counter: Int, w: Int, h: Int, rowStride: Int, flipWhite: Boolean): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(rowStride * h)
        val bits = PatternCodec.encode(counter)
        val y0 = ((PatternCodec.STRIP_Y - PatternCodec.STRIP_H / 2) * h).toInt()
        val y1 = ((PatternCodec.STRIP_Y + PatternCodec.STRIP_H / 2) * h).toInt()
        for (y in 0 until h) for (x in 0 until w) {
            var v = 40
            if (y in y0..y1) v = if (bits[(x * PatternCodec.BLOCKS / w).coerceAtMost(PatternCodec.BLOCKS - 1)]) 255 else 0
            else if (x in (w * 0.2).toInt()..(w * 0.8).toInt() && y in (h * 0.25).toInt()..(h * 0.9).toInt()) v = if (flipWhite) 255 else 0
            val p = y * rowStride + x * 4
            buf.put(p, v.toByte()); buf.put(p + 1, v.toByte()); buf.put(p + 2, v.toByte()); buf.put(p + 3, 255.toByte())
        }
        return buf
    }

    @Test fun roundTripAtSeveralScales() {
        for ((w, h) in listOf(780 to 360, 1040 to 480, 3120 to 1440)) {
            for (c in intArrayOf(0, 1, 2, 255, 256, 12345, 32768, 65535)) {
                val b = render(c, w, h, w * 4 + 64, false)
                assertEquals(c, PatternCodec.decode(b, w, h, w * 4 + 64, 4), "counter $c at ${w}x$h")
            }
        }
    }

    @Test fun corruptedStripIsRejected() {
        val w = 780; val h = 360
        val b = render(777, w, h, w * 4, false)
        // flip one counter block: parity must fail
        val y = (PatternCodec.STRIP_Y * h).toInt()
        val x = ((5 + 0.5f) / PatternCodec.BLOCKS * w).toInt()
        for (dx in -8..8) for (dy in -8..8) {
            val p = (y + dy) * w * 4 + (x + dx) * 4
            val old = b.get(p).toInt() and 0xFF
            val nv = if (old > 128) 0 else 255
            b.put(p, nv.toByte()); b.put(p + 1, nv.toByte()); b.put(p + 2, nv.toByte())
        }
        assertEquals(-1, PatternCodec.decode(b, w, h, w * 4, 4))
    }

    @Test fun flipLumaFollowsBox() {
        val w = 780; val h = 360
        assert(PatternCodec.flipLuma(render(1, w, h, w * 4, true), w, h, w * 4, 4) > 200)
        assert(PatternCodec.flipLuma(render(1, w, h, w * 4, false), w, h, w * 4, 4) < 50)
    }
}
