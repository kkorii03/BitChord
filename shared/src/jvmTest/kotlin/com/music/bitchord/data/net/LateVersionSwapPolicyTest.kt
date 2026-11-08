package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A music video whose song version is found after it became audible is handed
 * over to that version at the same bar, never left playing as the video when
 * the handover can be made without a jump. Before this, the late answer was
 * only used as a lyrics reference (round-score 8: 0 of 54 late answers played).
 *
 * The figures are the emulator's own: `-RJSbO8UZVY` has an 18.52 s video intro
 * (`song-sync ... shift=18520`) and its version landed about 21 s into the
 * video; `pB-5XG-DbAA` (30.46 s intro) landed 104 s after the lookup started.
 */
class LateVersionSwapPolicyTest {

    private fun refusal(
        musicOnly: Boolean = true,
        inParty: Boolean = false,
        casting: Boolean = false,
        listenerKeptVideo: Boolean = false,
        listenerChoseOriginal: Boolean = false,
        rowStillCurrent: Boolean = true,
        isSongVersion: Boolean = true,
        videoPositionMs: Long = 21_000L,
        videoDurationMs: Long? = 233_000L,
    ) = LateVersionSwapPolicy.refusal(
        musicOnly = musicOnly,
        inParty = inParty,
        casting = casting,
        listenerKeptVideo = listenerKeptVideo,
        listenerChoseOriginal = listenerChoseOriginal,
        rowStillCurrent = rowStillCurrent,
        isSongVersion = isSongVersion,
        videoPositionMs = videoPositionMs,
        videoDurationMs = videoDurationMs,
    )

    @Test
    fun `an audible video whose version lands early in the song is handed over`() {
        assertNull(refusal())
    }

    @Test
    fun `the song continues at the bar the video was on`() {
        // Video at 21.0 s, song = video - 18.52 s.
        assertEquals(2_480L, LateVersionSwapPolicy.songStartMs(21_000L, -18_520L, 213_000L))
        // A song that opens later than its video (offset > 0) is moved forward.
        assertEquals(26_000L, LateVersionSwapPolicy.songStartMs(25_000L, 1_000L, 200_000L))
    }

    @Test
    fun `a video still inside its own intro starts the song from its top`() {
        assertEquals(0L, LateVersionSwapPolicy.songStartMs(12_000L, -18_520L, 213_000L))
    }

    @Test
    fun `an unmeasured alignment is never folded into a zero shift`() {
        assertNull(LateVersionSwapPolicy.songStartMs(21_000L, null, 213_000L))
    }

    @Test
    fun `the handover stays within reach of the measured opening`() {
        val limit = LateVersionSwapPolicy.MAX_VIDEO_POSITION_MS
        assertEquals(75_000L, limit)
        assertNull(refusal(videoPositionMs = limit))
        assertEquals(
            LateVersionSwapPolicy.Refusal.PAST_MEASURED_OPENING,
            refusal(videoPositionMs = limit + 1),
        )
        assertNull(LateVersionSwapPolicy.songStartMs(limit + 1, -30_460L, 210_000L))
        // pB-5XG-DbAA's answer at 104 s after the lookup started, with ~20 s
        // to the first sound, is ~84 s into the video: too far to trust.
        assertNull(LateVersionSwapPolicy.songStartMs(84_000L, -30_460L, 210_000L))
    }

    @Test
    fun `no handover in the last stretch of either recording`() {
        assertEquals(
            LateVersionSwapPolicy.Refusal.NEAR_END,
            refusal(videoPositionMs = 60_000L, videoDurationMs = 75_000L),
        )
        assertNull(refusal(videoPositionMs = 60_000L, videoDurationMs = null))
        // The song itself would be within 20 s of its end.
        assertNull(LateVersionSwapPolicy.songStartMs(70_000L, -10_000L, 75_000L))
    }

    @Test
    fun `the listener's own choices and shared playback are never overridden`() {
        assertEquals(LateVersionSwapPolicy.Refusal.MUSIC_ONLY_OFF, refusal(musicOnly = false))
        assertEquals(LateVersionSwapPolicy.Refusal.LISTEN_TOGETHER, refusal(inParty = true))
        assertEquals(LateVersionSwapPolicy.Refusal.CASTING, refusal(casting = true))
        assertEquals(LateVersionSwapPolicy.Refusal.LISTENER_KEPT_VIDEO, refusal(listenerKeptVideo = true))
        assertEquals(
            LateVersionSwapPolicy.Refusal.LISTENER_CHOSE_ORIGINAL,
            refusal(listenerChoseOriginal = true),
        )
    }

    @Test
    fun `a stale answer or a non-version is not installed`() {
        assertEquals(LateVersionSwapPolicy.Refusal.ROW_CHANGED, refusal(rowStillCurrent = false))
        assertEquals(LateVersionSwapPolicy.Refusal.NOT_A_SONG_VERSION, refusal(isSongVersion = false))
    }

    @Test
    fun `a negative position is read as the start`() {
        assertEquals(0L, LateVersionSwapPolicy.songStartMs(-50L, -5_000L, 200_000L))
        assertNull(LateVersionSwapPolicy.positionRefusal(-50L, 200_000L))
    }
}
