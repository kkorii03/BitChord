package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The retry is owed once per track, and it must carry the exact lookup row so
 * a synthetic `typeUnknown` or a stripped local file is not lost.
 */
class SongVersionRetryGateTest {

    private fun row() = Song(
        videoId = "sCz5y84dwuA",
        title = "(It Goes Like) Nanana",
        artist = "Peggy Gou",
        thumbnailUrl = null,
        isVideo = true,
        typeUnknown = true,
    )

    @Test
    fun `a pending row is spent exactly once`() {
        val gate = SongVersionRetryGate()
        val lookup = row()
        gate.markPending(lookup)

        assertSame(lookup, gate.takePending(lookup.videoId))
        assertNull("the retry is spent once", gate.takePending(lookup.videoId))
    }

    @Test
    fun `a track already retried cannot queue another`() {
        val gate = SongVersionRetryGate()
        val lookup = row()
        gate.markPending(lookup)
        assertSame(lookup, gate.takePending(lookup.videoId))

        gate.markPending(lookup)
        assertNull(gate.takePending(lookup.videoId))
    }

    @Test
    fun `the lookup row keeps its synthesized fields`() {
        val gate = SongVersionRetryGate()
        val lookup = row().copy(localUri = null, localPath = null, typeUnknown = true)
        gate.markPending(lookup)

        val taken = gate.takePending(lookup.videoId)
        assertEquals(true, taken?.typeUnknown)
        assertNull(taken?.localUri)
    }

    @Test
    fun `a blank id is ignored`() {
        val gate = SongVersionRetryGate()
        gate.markPending(row().copy(videoId = ""))
        assertNull(gate.takePending(""))
    }
}
