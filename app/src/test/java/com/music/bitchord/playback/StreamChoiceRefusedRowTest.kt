package com.music.bitchord.playback

import android.os.SystemClock
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.smart.LyricsTiming
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review 11-17 (RG1-2). A lapsed substitute choice was let go only by a fresh
 * resolve, a saved-file replay or a forget. A refused row is never resolved
 * again, so the next play opened YouTube's whole cached copy without a resolve
 * and the lyrics stayed on the refused copy's clock for good.
 */
class StreamChoiceRefusedRowTest {

    private val ids = (1..5).map { "refused-row-$it" }
    private val substitute = LyricsTiming.Clock.Substitute("src", "trk")

    @Before
    @After
    fun reset() {
        ids.forEach(StreamChoice::forget)
        StreamChoice.onTrackBecameCurrent(null)
        Downloads.setSavedMetadata(emptyMap())
    }

    private fun copy() = SourceStream(
        url = "https://tidal.example/track.flac",
        format = StreamFormat(codec = "flac", kbps = 1411),
        sourceConfigId = "src",
        trackId = "trk",
    )

    private fun lapse(id: String) {
        assertNull(StreamChoice.of(id, nowMs = SystemClock.elapsedRealtime() + 16 * 60 * 1000L))
    }

    @Test
    fun `a refusal after the play lets go of the lapsed substitute's clock`() {
        StreamChoice.remember(ids[0], copy(), substituted = true)
        lapse(ids[0])
        assertEquals(substitute, LyricsTiming.playingClock(ids[0]))

        StreamChoice.onRowRefused(ids[0], "src", "trk")

        assertNull(StreamChoice.sounding(ids[0]))
        assertTrue(LyricsTiming.playingClock(ids[0]) !is LyricsTiming.Clock.Substitute)
    }

    @Test
    fun `the play still sounding the refused copy keeps its clock until the queue moves`() {
        StreamChoice.onTrackBecameCurrent(ids[1])
        StreamChoice.remember(ids[1], copy(), substituted = true)
        lapse(ids[1])

        StreamChoice.onRowRefused(ids[1], "src", "trk")
        assertEquals(substitute, LyricsTiming.playingClock(ids[1]))

        StreamChoice.onTrackBecameCurrent(ids[2])
        assertNull(StreamChoice.sounding(ids[1]))
    }

    @Test
    fun `a repeat of the song is a new play and opens no refused copy`() {
        StreamChoice.onTrackBecameCurrent(ids[2])
        StreamChoice.remember(ids[2], copy(), substituted = true)
        lapse(ids[2])
        StreamChoice.onRowRefused(ids[2], "src", "trk")

        StreamChoice.onTrackBecameCurrent(ids[2])

        assertNull(StreamChoice.sounding(ids[2]))
    }

    @Test
    fun `another row's refusal leaves the clock alone`() {
        StreamChoice.remember(ids[3], copy(), substituted = true)
        lapse(ids[3])

        StreamChoice.onRowRefused(ids[3], "src", "other")
        StreamChoice.onTrackBecameCurrent("elsewhere")

        assertEquals(substitute, LyricsTiming.playingClock(ids[3]))
    }

    @Test
    fun `a refused row's live choice is kept, and not kept once it lapses`() {
        StreamChoice.remember(ids[4], copy(), substituted = true)
        StreamChoice.onRowRefused(ids[4], "src", "trk")
        // Live: it stands behind a half-filled entry only its own stream may finish.
        assertNotNull(StreamChoice.of(ids[4]))

        lapse(ids[4])

        assertNull(StreamChoice.sounding(ids[4]))
    }

    @Test
    fun `the refusal and the queue move reach the choice`() {
        val root = File(".").canonicalFile
        fun source(path: String) = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/$path") }
            .first { it.exists() }.readText()
        val reject = source("playback/smart/HqAudioVerify.kt").substringAfter("private fun reject(")
            .substringBefore("private fun noteNoReference(")
        assertTrue(reject.contains("StreamChoice.onRowRefused(youtubeId, sourceId, trackId)"))
        val moved = source("playback/AudioCache.kt").substringAfter("fun onTrackBecameCurrent(mediaId: String?)")
            .substringBefore("fun cancelWholeRenditionsExcept(")
        assertTrue(moved.contains("StreamChoice.onTrackBecameCurrent(mediaId)"))
        assertTrue(moved.contains("koLossless.onTrackBecameCurrent(mediaId)"))
    }
}
