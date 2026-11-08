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
    fun `a same-id release is never aligned against itself while audible`() {
        assertEquals(
            LateVersionSwapPolicy.Refusal.SAME_UPLOAD,
            LateVersionSwapPolicy.refusal(
                musicOnly = true, inParty = false, casting = false,
                listenerKeptVideo = false, listenerChoseOriginal = false,
                rowStillCurrent = true, isSongVersion = true,
                videoPositionMs = 21_000L, videoDurationMs = 233_000L,
                sameUpload = true,
            ),
        )
    }

    @Test
    fun `smart alignment off and low power keep the video`() {
        fun ask(smart: Boolean, low: Boolean) = LateVersionSwapPolicy.refusal(
            musicOnly = true, inParty = false, casting = false,
            listenerKeptVideo = false, listenerChoseOriginal = false,
            rowStillCurrent = true, isSongVersion = true,
            videoPositionMs = 21_000L, videoDurationMs = 233_000L,
            smartAlignment = smart, lowPower = low,
        )
        assertEquals(LateVersionSwapPolicy.Refusal.SMART_ALIGNMENT_OFF, ask(smart = false, low = false))
        assertEquals(LateVersionSwapPolicy.Refusal.LOW_POWER, ask(smart = true, low = true))
        assertNull(ask(smart = true, low = false))
    }

    @Test
    fun `a silent row still obeys the listener and the session but not the alignment rules`() {
        fun silent(
            musicOnly: Boolean = true,
            inParty: Boolean = false,
            casting: Boolean = false,
            kept: Boolean = false,
            pinned: Boolean = false,
        ) = LateVersionSwapPolicy.refusal(
            musicOnly = musicOnly, inParty = inParty, casting = casting,
            listenerKeptVideo = kept, listenerChoseOriginal = pinned,
            rowStillCurrent = true, isSongVersion = true,
            videoPositionMs = 0L, videoDurationMs = null,
            audible = false, sameUpload = true, smartAlignment = false, lowPower = true,
        )
        assertNull(silent())
        assertEquals(LateVersionSwapPolicy.Refusal.MUSIC_ONLY_OFF, silent(musicOnly = false))
        assertEquals(LateVersionSwapPolicy.Refusal.LISTEN_TOGETHER, silent(inParty = true))
        assertEquals(LateVersionSwapPolicy.Refusal.CASTING, silent(casting = true))
        assertEquals(LateVersionSwapPolicy.Refusal.LISTENER_KEPT_VIDEO, silent(kept = true))
        assertEquals(LateVersionSwapPolicy.Refusal.LISTENER_CHOSE_ORIGINAL, silent(pinned = true))
    }

    @Test
    fun `the route installs a silent row, hands an audible one over, and otherwise only measures`() {
        val route = LateVersionSwapPolicy::route
        assertEquals(LateVersionSwapPolicy.Route.INSTALL_BEFORE_AUDIO, route(true, true, null))
        assertEquals(LateVersionSwapPolicy.Route.HAND_OVER, route(true, false, null))
        assertEquals(LateVersionSwapPolicy.Route.LYRICS_ONLY, route(false, true, null))
        assertEquals(LateVersionSwapPolicy.Route.LYRICS_ONLY, route(false, false, null))
        assertEquals(
            LateVersionSwapPolicy.Route.LYRICS_ONLY,
            route(true, true, LateVersionSwapPolicy.Refusal.MUSIC_ONLY_OFF),
        )
        assertEquals(
            LateVersionSwapPolicy.Route.LYRICS_ONLY,
            route(true, false, LateVersionSwapPolicy.Refusal.NOT_MEASURED),
        )
    }

    @Test
    fun `the exact boundaries are inclusive`() {
        // 75 000 ms is still within reach; exactly 20 000 ms left is enough.
        assertNull(LateVersionSwapPolicy.positionRefusal(75_000L, 95_000L))
        assertEquals(
            LateVersionSwapPolicy.Refusal.NEAR_END,
            LateVersionSwapPolicy.positionRefusal(75_000L, 94_999L),
        )
        assertEquals(55_000L, LateVersionSwapPolicy.songStartMs(75_000L, -20_000L, 75_000L))
        assertNull(LateVersionSwapPolicy.songStartMs(75_000L, -20_000L, 74_999L))
    }

    @Test
    fun `a negative position is read as the start`() {
        assertEquals(0L, LateVersionSwapPolicy.songStartMs(-50L, -5_000L, 200_000L))
        assertNull(LateVersionSwapPolicy.positionRefusal(-50L, 200_000L))
    }
}
