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
 *   is casting, or music-only is off;
 * - the listener turned smart version alignment off, or low power is on.
 *
 * Event-driven: it is asked when the late lookup answers, again with live
 * state after the alignment, and at each step of the (seconds-long) handover
 * that already runs its own short waits. Nothing new is polled.
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

    /**
     * How long the handover waits for the standby player to be ready. Longer
     * than a manual switch's 7 s: the video keeps playing meanwhile, so the
     * wait is not heard.
     */
    const val STANDBY_WAIT_MS = 15_000L

    /**
     * Wall-clock time an alignment pass started at [videoPositionMs] may take
     * before its answer is useless: once the playhead passes
     * [MAX_VIDEO_POSITION_MS] the handover is refused anyway, so a pass that
     * runs past that point only burns the battery. At [speed] the playhead
     * covers the room faster or slower than the wall clock. 0 when there is no
     * room left. Never more than [MAX_VIDEO_POSITION_MS] at normal speed.
     */
    fun passBudgetMs(videoPositionMs: Long, speed: Float): Long {
        val room = MAX_VIDEO_POSITION_MS - videoPositionMs.coerceAtLeast(0L)
        if (room <= 0L) return 0L
        // Slower than half speed is read as half: the budget stays bounded.
        val rate = speed.takeIf { it.isFinite() && it > 0f }?.coerceAtLeast(0.5f) ?: 1f
        return (room / rate).toLong()
    }

    /** What to do with music-only's own audio-version answer when it lands after the cap. */
    enum class LateAudioAnswer {
        /** The row is the playing one: hand it to the late-version route. */
        HAND_OVER,

        /** A row that has not started yet: let music-only convert it now. */
        CONVERT_UPCOMING,

        /** Nothing usable: no version, the video itself, or the row has gone. */
        DROP,
    }

    /**
     * Music-only's audio-version lookup gives up holding the video after its
     * cap, but the answer may still come. It used to be cancelled at the cap,
     * so a version found at 16 s was never heard; now the late answer goes
     * where SongVersionController's own late answers go.
     *
     * [isSongVersion] is SongVersionPolicy.isSongVersionOf(video, answer).
     */
    fun lateAudioAnswer(
        isSongVersion: Boolean,
        rowIsCurrent: Boolean,
        rowIsUpcoming: Boolean,
    ): LateAudioAnswer = when {
        !isSongVersion -> LateAudioAnswer.DROP
        rowIsCurrent -> LateAudioAnswer.HAND_OVER
        rowIsUpcoming -> LateAudioAnswer.CONVERT_UPCOMING
        else -> LateAudioAnswer.DROP
    }

    enum class Refusal(val reason: String) {
        MUSIC_ONLY_OFF("music only off"),
        LISTEN_TOGETHER("listen together"),
        CASTING("casting"),
        LISTENER_KEPT_VIDEO("listener chose the video"),
        LISTENER_CHOSE_ORIGINAL("listener chose the original upload"),
        ROW_CHANGED("row is no longer playing"),
        NOT_A_SONG_VERSION("not a song version"),
        SAME_UPLOAD("same upload id, nothing to align"),
        SMART_ALIGNMENT_OFF("smart version alignment off"),
        LOW_POWER("low power"),
        NOT_MEASURED("alignment not measured"),
        PAST_MEASURED_OPENING("past the measured opening"),
        NEAR_END("near the end"),
    }

    /**
     * Why the late song version must not take the video's place, or null when
     * it may. Asked when the late lookup answers and again, with live state,
     * after the alignment and at every step of the handover — a listener's
     * revert, a party, a cast or music-only turned off in between wins.
     *
     * [audible] false is a row that has not made its first sound yet: the
     * release takes it exactly as before audio, so only the listener's and the
     * session's rules apply, not the alignment ones.
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
        audible: Boolean = true,
        sameUpload: Boolean = false,
        smartAlignment: Boolean = true,
        lowPower: Boolean = false,
    ): Refusal? = when {
        !musicOnly -> Refusal.MUSIC_ONLY_OFF
        inParty -> Refusal.LISTEN_TOGETHER
        casting -> Refusal.CASTING
        listenerKeptVideo -> Refusal.LISTENER_KEPT_VIDEO
        listenerChoseOriginal -> Refusal.LISTENER_CHOSE_ORIGINAL
        !rowStillCurrent -> Refusal.ROW_CHANGED
        !isSongVersion -> Refusal.NOT_A_SONG_VERSION
        !audible -> null
        // The aligner's cache answers 0 for one id without measuring it, so
        // an audible same-id swap would be exactly the unmeasured zero shift
        // this policy exists to refuse.
        sameUpload -> Refusal.SAME_UPLOAD
        !smartAlignment -> Refusal.SMART_ALIGNMENT_OFF
        lowPower -> Refusal.LOW_POWER
        else -> positionRefusal(videoPositionMs, videoDurationMs)
    }

    /** What to do with a song version that answered after the first-sound budget. */
    enum class Route {
        /** Still silent: the release takes the row exactly as before audio. */
        INSTALL_BEFORE_AUDIO,

        /** Audible: hand over at the same bar (lyrics are measured meanwhile). */
        HAND_OVER,

        /** Refused or a deliberate performance: the video plays, lyrics are measured. */
        LYRICS_ONLY,
    }

    /**
     * [mayInstall] is SongVersionPolicy.mayInstallSongVersion (never a cover or
     * a live take); [refusal] is [refusal] asked with `audible = !stillSilent`.
     */
    fun route(mayInstall: Boolean, stillSilent: Boolean, refusal: Refusal?): Route = when {
        !mayInstall || refusal != null -> Route.LYRICS_ONLY
        stillSilent -> Route.INSTALL_BEFORE_AUDIO
        else -> Route.HAND_OVER
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
