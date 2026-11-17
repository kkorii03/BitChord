package com.music.bitchord.ko

import androidx.media3.common.Player

/**
 * [KO] P1-1
 *
 * Whether headphones arriving may pick a paused song back up.
 *
 * A fresh pause is not enough on its own: a pause the sleep timer ("after this
 * song" included) made, or one another app forced by taking audio focus, is not
 * one the listener wants undone the moment earbuds reconnect. Those set a veto
 * that only real playback clears.
 */
internal object KoHeadphoneResumePolicy {

    fun mayResume(stoppedAtMs: Long, nowMs: Long, vetoed: Boolean, windowMs: Long): Boolean {
        if (vetoed) return false
        if (stoppedAtMs == 0L) return false
        return nowMs - stoppedAtMs <= windowMs
    }

    /** A pause another app imposed by taking audio focus away for good. */
    fun vetoesPause(playWhenReady: Boolean, reason: Int): Boolean =
        !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
}
