package com.music.bitchord.data.net

/**
 * When a music video's song version is found only after the video has become
 * audible, and whether the playing video may still be handed over to it.
 *
 * The song version is what music-only promises, so the late answer is used
 * rather than filed away for the next playback. The handover is the player's
 * existing two-player version swap: the song's position is the video's
 * position through the measured alignment, and the two are cross-faded, so the
 * listener hears the same bar continue on the release instead of a jump.
 *
 * It is refused, and the video keeps playing (with its lyrics measured against
 * the release as before), whenever that cannot be promised:
 * - no trusted alignment: an unmeasured pair would land on the wrong second;
 * - the playhead is too far past the opening the alignment was measured on;
 * - too little of the song is left to be worth a handover;
 * - the listener chose the video or the original upload, or is in a party, or
 *   is casting, or music-only is off.
 *
 * Event-driven: it is asked once, when the late lookup answers, and once more
 * just before the swap commits (after the alignment, which takes time). It is
 * never polled.
 */
object LateVersionSwapPolicy {

    /**
     * The alignment is measured on the first [ALIGNMENT_WINDOW_MS] of both
     * recordings (see VersionAudioAligner), one offset for the whole song. The
     * further the playhead is past that window, the more room there is for a
     * video-only insert (a skit, a dance break) between the measured opening
     * and the bar being handed over, which would put the song on the wrong
     * second. Thirty seconds past the window is the bound: in the 84 emulator
     * runs of round-score 8 every late answer for an audible video landed by
     * 72 s into it (median 25 s), so this keeps all of them.
     */
    const val ALIGNMENT_WINDOW_MS = 45_000L
    const val MAX_VIDEO_POSITION_MS = ALIGNMENT_WINDOW_MS + 30_000L

    /**
     * A handover this close to the end changes nothing the listener would
     * notice except the swap itself, and a video's last stretch is often its
     * credits or a skit the song does not have.
     */
    const val MIN_REMAINING_MS = 20_000L

    enum class Refusal(val reason: String) {
        MUSIC_ONLY_OFF("music only off"),
        LISTEN_TOGETHER("listen together"),
        CASTING("casting"),
        LISTENER_KEPT_VIDEO("listener chose the video"),
        LISTENER_CHOSE_ORIGINAL("listener chose the original upload"),
        ROW_CHANGED("row is no longer playing"),
        NOT_A_SONG_VERSION("not a song version"),
        NOT_MEASURED("alignment not measured"),
        PAST_MEASURED_OPENING("past the measured opening"),
        NEAR_END("near the end"),
    }

    /**
     * Why the audible video must not be handed over to its late song version,
     * or null when it may. Everything here is known the moment the late lookup
     * answers; the alignment is checked separately by [songStartMs] once it has
     * been measured.
     */
    fun refusal(
        musicOnly: Boolean,
        inParty: Boolean,
        casting: Boolean,
        listenerKeptVideo: Boolean,
        listenerChoseOriginal: Boolean,
        rowStillCurrent: Boolean,
        isSongVersion: Boolean,
        videoPositionMs: Long,
        videoDurationMs: Long?,
    ): Refusal? = when {
        !musicOnly -> Refusal.MUSIC_ONLY_OFF
        inParty -> Refusal.LISTEN_TOGETHER
        casting -> Refusal.CASTING
        listenerKeptVideo -> Refusal.LISTENER_KEPT_VIDEO
        listenerChoseOriginal -> Refusal.LISTENER_CHOSE_ORIGINAL
        !rowStillCurrent -> Refusal.ROW_CHANGED
        !isSongVersion -> Refusal.NOT_A_SONG_VERSION
        else -> positionRefusal(videoPositionMs, videoDurationMs)
    }

    /** The playhead part of [refusal], also re-checked after the alignment. */
    fun positionRefusal(videoPositionMs: Long, videoDurationMs: Long?): Refusal? {
        val position = videoPositionMs.coerceAtLeast(0L)
        if (position > MAX_VIDEO_POSITION_MS) return Refusal.PAST_MEASURED_OPENING
        val duration = videoDurationMs?.takeIf { it > 0L }
        if (duration != null && duration - position < MIN_REMAINING_MS) return Refusal.NEAR_END
        return null
    }

    /**
     * Where the song version starts so the bar the listener is hearing
     * continues: the video's position plus the measured [offsetMs] (target =
     * source + offset, the aligner's convention).
     *
     * A video still inside its own intro maps before the song's first second;
     * nothing of the song has been heard yet, so the song starts from its top.
     *
     * Null refuses the swap: no trusted offset (an unmeasured pair must not be
     * folded into a zero shift — that is the jump this exists to avoid), a
     * playhead past the measured opening, or a mapped position too close to
     * the song's own end.
     */
    fun songStartMs(
        videoPositionMs: Long,
        offsetMs: Long?,
        songDurationMs: Long?,
    ): Long? {
        if (offsetMs == null) return null
        val position = videoPositionMs.coerceAtLeast(0L)
        if (position > MAX_VIDEO_POSITION_MS) return null
        val start = (position + offsetMs).coerceAtLeast(0L)
        val duration = songDurationMs?.takeIf { it > 0L }
        if (duration != null && duration - start < MIN_REMAINING_MS) return null
        return start
    }
}
