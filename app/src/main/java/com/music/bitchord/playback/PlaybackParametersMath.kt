package com.music.bitchord.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.pow

internal object PlaybackParametersMath {
    const val MIN_PITCH_SEMITONES = -6
    const val MAX_PITCH_SEMITONES = 6

    /** The speeds the player's picker offers; a stored value outside this is not a choice. */
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2.0f
    const val DEFAULT_SPEED = 1.0f

    const val MIN_REPEAT_MODE = Player.REPEAT_MODE_OFF
    const val MAX_REPEAT_MODE = Player.REPEAT_MODE_ALL

    fun clampPitchSemitones(semitones: Int): Int =
        semitones.coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES)

    /** Media3 requires a finite, positive speed; NaN and zero must never reach it. */
    fun clampSpeed(speed: Float): Float =
        speed.takeIf { it.isFinite() }?.coerceIn(MIN_SPEED, MAX_SPEED) ?: DEFAULT_SPEED

    fun clampRepeatMode(mode: Int): Int =
        mode.coerceIn(MIN_REPEAT_MODE, MAX_REPEAT_MODE)

    fun pitchForSemitones(semitones: Int): Float =
        2.0.pow(clampPitchSemitones(semitones) / 12.0).toFloat()

    fun parameters(speed: Float, semitones: Int): PlaybackParameters =
        PlaybackParameters(speed, pitchForSemitones(semitones))
}

internal fun ExoPlayer.applyPlaybackSpeed(
    speed: Float,
    semitones: Int = AppSettings.pitchSemitones.value,
) {
    setPlaybackParameters(PlaybackParametersMath.parameters(speed, semitones))
}
