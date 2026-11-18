package com.music.bitchord.ko

import com.music.bitchord.playback.smart.AudioDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * oom2: the stereo window decode used to keep both channels' chunk lists alive
 * until both joined copies existed (four channel-sized arrays at once, ~35 MB
 * for a 45 s 48 kHz window). Each channel's chunks are now let go as soon as
 * that channel is joined. AudioDecoder.flattenReleasing does not exist on the
 * old code, so this class fails there.
 */
class KoStereoFlattenTest {
    @Test
    fun joinsInOrderAndLetsTheChunksGo() {
        val chunks = mutableListOf(floatArrayOf(1f, 2f), floatArrayOf(), floatArrayOf(3f, 4f, 5f))
        val joined = AudioDecoder.flattenReleasing(chunks)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f), joined, 0f)
        assertTrue("chunks must be released after the join", chunks.isEmpty())
    }

    @Test
    fun anEmptyRegionJoinsToNothing() {
        val chunks = mutableListOf<FloatArray>()
        assertArrayEquals(FloatArray(0), AudioDecoder.flattenReleasing(chunks), 0f)
    }
}
