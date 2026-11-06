package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edges around the mid-song lossless swap that the first test file does not
 * exercise: a player that has not reported a duration yet, the exact
 * twenty-second floor, and a stored verdict whose rate cannot be used to move
 * the playhead.
 */
class HqSwapPolicyEdgeTest {

    @Test
    fun `a player without a duration is not treated as almost over`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 0L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
        )

        assertTrue(decision.allowed)
        assertEquals(40_000L, decision.targetPositionMs)
    }

    @Test
    fun `exactly twenty seconds left is still worth the swap`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 180_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun `one millisecond under the floor is refused`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 180_001L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
        )

        assertFalse(decision.allowed)
        assertEquals("too-late", decision.reason)
    }

    @Test
    fun `a target exactly at the end is not a position inside the file`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 160_000L,
            rate = 1.0,
        )

        assertFalse(decision.allowed)
        assertEquals("position-out-of-range", decision.reason)
    }

    @Test
    fun `a stored verdict with a zero rate cannot move the playhead`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 1_200L,
            rate = 0.0,
        )

        assertFalse("a zero rate would seek every position to the offset", decision.allowed)
        assertEquals("bad-rate", decision.reason)
    }

    @Test
    fun `a stored verdict with a negative rate is refused`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = -1.0,
        )

        assertFalse(decision.allowed)
        assertEquals("bad-rate", decision.reason)
    }

    @Test
    fun `a stored verdict with a non-finite rate is refused`() {
        val nan = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = Double.NaN,
        )
        val infinite = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = Double.POSITIVE_INFINITY,
        )

        assertFalse(nan.allowed)
        assertEquals("bad-rate", nan.reason)
        assertFalse(infinite.allowed)
        assertEquals("bad-rate", infinite.reason)
    }

    @Test
    fun `a corrupt current rate refuses the swap instead of using the raw position`() {
        val zero = HqSwapPolicy.decide(
            positionMs = 42_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 2_000L,
            rate = 1.0,
            currentRate = 0.0,
        )
        val nan = HqSwapPolicy.decide(
            positionMs = 42_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 2_000L,
            rate = 1.0,
            currentRate = Double.NaN,
        )

        assertFalse(zero.allowed)
        assertEquals("bad-current-rate", zero.reason)
        assertFalse(nan.allowed)
        assertEquals("bad-current-rate", nan.reason)
    }

    @Test
    fun `a swap that never adopted must release the standby it armed`() {
        assertTrue(
            "a cancelled swap would leave the spare playing the old song on mute",
            HqSwapPolicy.standbyMustBeReleased(armed = true, adopted = false),
        )
        assertFalse(
            "an adopted swap handed the session over; the spare is the old player",
            HqSwapPolicy.standbyMustBeReleased(armed = true, adopted = true),
        )
        assertFalse(
            "a swap that never armed a standby has nothing to release",
            HqSwapPolicy.standbyMustBeReleased(armed = false, adopted = false),
        )
    }

    @Test
    fun `a video radio row is never swapped to a catalogue master`() {
        val radio = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
            videoRadio = true,
        )
        val video = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
            isVideo = true,
        )

        assertFalse(radio.allowed)
        assertEquals("video-radio", radio.reason)
        assertFalse(video.allowed)
        assertEquals("video-radio", video.reason)
    }

    private fun unfit(
        mediaId: String? = "song",
        index: Int = 3,
        state: Int = Player.STATE_READY,
        positionMs: Long = 60_000L,
        durationMs: Long = 200_000L,
        suppressed: Boolean = false,
    ): String? = HqSwapPolicy.standbyUnfitReason(
        expectedMediaId = "song",
        expectedIndex = 3,
        standbyMediaId = mediaId,
        standbyIndex = index,
        standbyState = state,
        standbyPositionMs = positionMs,
        standbyDurationMs = durationMs,
        suppressed = suppressed,
    )

    @Test
    fun `S1 a standby still on its row with room left may be blended`() {
        assertNull(unfit())
        assertNull("a seek masks the state as buffering", unfit(state = Player.STATE_BUFFERING))
    }

    @Test
    fun `S1 a standby that ran off its file onto the next song is not adopted`() {
        assertEquals("standby-moved", unfit(mediaId = "next-song", index = 4))
        assertEquals("the same song queued twice is still another row", "standby-moved", unfit(index = 4))
    }

    @Test
    fun `S1 a replacement shorter than the playhead is not adopted`() {
        assertEquals("standby-too-short", unfit(positionMs = 150_000L, durationMs = 120_000L))
        assertEquals("standby-too-short", unfit(positionMs = 120_000L, durationMs = 120_000L))
    }

    @Test
    fun `S1 a replacement with no length yet is not called short`() {
        assertNull(unfit(positionMs = 150_000L, durationMs = C.TIME_UNSET))
    }

    @Test
    fun `S7 a standby that failed or ended mid-blend is not adopted as silence`() {
        assertEquals("standby-stopped", unfit(state = Player.STATE_IDLE))
        assertEquals("standby-stopped", unfit(state = Player.STATE_ENDED))
    }

    @Test
    fun `S2 a row added behind the current one during the swap is reported`() {
        val edits = HqSwapPolicy.queueEdits(listOf("a", "song", "b", "added"), listOf("a", "song*", "b"), 1)
        assertEquals(HqSwapPolicy.QueueEdits(head = false, tail = true), edits)
    }

    @Test
    fun `S2 a row removed behind the current one during the swap is reported`() {
        val edits = HqSwapPolicy.queueEdits(listOf("a", "song"), listOf("a", "song*", "removed"), 1)
        assertEquals(HqSwapPolicy.QueueEdits(head = false, tail = true), edits)
    }

    @Test
    fun `S2 rows reordered ahead of the current one are reported`() {
        val edits = HqSwapPolicy.queueEdits(listOf("b", "a", "song"), listOf("a", "b", "song*"), 2)
        assertEquals(HqSwapPolicy.QueueEdits(head = true, tail = false), edits)
    }

    @Test
    fun `S2 the upgraded current row alone is not an edit`() {
        val edits = HqSwapPolicy.queueEdits(listOf("a", "song", "b"), listOf("a", "song*", "b"), 1)
        assertEquals(HqSwapPolicy.QueueEdits(head = false, tail = false), edits)
    }

    @Test
    fun `S3 a call holding the session refuses the swap`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
            suppressed = true,
        )
        assertFalse(decision.allowed)
        assertEquals("suppressed", decision.reason)
    }

    @Test
    fun `S3 a call arriving during the prepare or blend stops the standby`() {
        assertEquals("suppressed", unfit(suppressed = true))
    }

    @Test
    fun `S5 play pressed while a paused swap was preparing makes its standby stale`() {
        assertTrue(HqSwapPolicy.pausedStandbyStale(true, 60_000L, 60_000L))
    }

    @Test
    fun `S5 a seek while a paused swap was preparing makes its standby stale`() {
        assertTrue(HqSwapPolicy.pausedStandbyStale(false, 60_000L, 90_000L))
        assertFalse(HqSwapPolicy.pausedStandbyStale(false, 60_000L, 60_000L))
    }

    @Test
    fun `S5 a seek during the blend keeps the old copy instead of jumping back`() {
        assertTrue(HqSwapPolicy.standbyOffTarget(60_000L, 90_000L))
        assertTrue(HqSwapPolicy.standbyOffTarget(90_000L, 60_000L))
        assertFalse("the blend itself leaves the sides this close", HqSwapPolicy.standbyOffTarget(60_000L, 60_300L))
    }

    @Test
    fun `S6 a substitute whose clock became unknown during the download refuses the swap`() {
        val decision = HqSwapPolicy.decide(
            positionMs = 40_000L,
            durationMs = 200_000L,
            crossfadeActive = false,
            sameTrack = true,
            offsetMs = 0L,
            rate = 1.0,
            currentClockKnown = false,
        )
        assertFalse(decision.allowed)
        assertEquals("current-clock-unknown", decision.reason)
    }

    @Test
    fun `S1 S2 S5 S7 the guards are wired into the swap itself`() {
        val swap = swapBody()
        val loop = swap.substringAfter("while (isActive)").substringBefore("if (progress >= 1f) break")
        assertTrue("the blend must read the standby every tick", "unfitReason()" in loop)
        assertTrue("the paused path must notice the listener moving", "pausedStandbyStale(" in swap)
        val adopt = swap.indexOf("adoptPlayerForVersionSwap(")
        assertTrue("the queue is reconciled before the handover", swap.indexOf("queueEdits(") in 0 until adopt)
        assertTrue("the position is read before the handover", swap.indexOf("standbyOffTarget(") in 0 until adopt)
    }

    @Test
    fun `S3 S6 the decision is given the suppression and the clock`() {
        val decision = source(SWAP_FILE)
            .substringAfter("fun PlaybackService.koHqSwapDecision(")
            .substringBefore("fun PlaybackService.koCurrentPlayingVerification(")
        assertTrue("suppressed =" in decision)
        assertTrue("currentClockKnown =" in decision)
    }

    @Test
    fun `S4 a revert names the source captured before the swap overwrote it`() {
        val swap = swapBody()
        val captured = swap.indexOf("NerdStats.sourceFor(mediaId)")
        assertTrue("mid-song swap must capture it first", captured in 0 until swap.indexOf("NerdStats.onSourceStream("))
        val service = source(SERVICE_FILE)
        val watch = service.substringAfter("internal fun watchUpgrade(").substringBefore("/** What [resolveWithModulePriority]")
        assertFalse(
            "the choice already names the replacement by the time a revert runs",
            "StreamChoice.of(mediaId)" in watch,
        )
        val audition = service.substringAfter("val previousFormat = NerdStats.declaredFormat(mediaId)\n")
        assertTrue(
            "the audition path must capture it too",
            audition.indexOf("NerdStats.sourceFor(mediaId)") in 0 until audition.indexOf("NerdStats.onSourceStream("),
        )
    }

    private fun swapBody(): String = source(SWAP_FILE)
        .substringAfter("fun PlaybackService.koSmoothSwapToLossless(")
        .substringBefore("fun PlaybackService.koIsStillSwapping(")

    private fun source(relative: String): String {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return File(directory, relative).readText().replace("\r\n", "\n")
    }

    private companion object {
        const val SWAP_FILE = "app/src/main/java/com/music/bitchord/ko/KoPlaybackLosslessSwap.kt"
        const val SERVICE_FILE = "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt"
    }
}
