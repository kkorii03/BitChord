package com.music.bitchord.ko

import androidx.media3.common.Player
import com.music.bitchord.playback.AudioRouting

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

    /**
     * [KO] btroute: headphones leaving must pause, the way Android's "becoming noisy" does.
     *
     * 11-20 17:20, JBL Tour Pro 3 flapping: a Bluetooth codec change rebuilt the players
     * (`live output switch`) right as the earbuds went; the broadcast reached the player that
     * was being replaced, the new one carried play on, and the song came out of the phone's
     * speaker for 9 s. So the device callback decides it from the route itself: the music was
     * on an external output ([wasKind]) and now falls to the phone ([nowKind] PHONE or none).
     * The pause is the listener's kind (not a veto), so the existing headphones-back rule
     * ([mayResume]) treats it exactly like a noisy pause.
     */
    fun pausesOnRouteLoss(playWhenReady: Boolean, wasKind: AudioRouting.Kind?, nowKind: AudioRouting.Kind?): Boolean {
        if (!playWhenReady) return false
        if (wasKind == null || wasKind == AudioRouting.Kind.PHONE) return false
        return nowKind == null || nowKind == AudioRouting.Kind.PHONE
    }
}
