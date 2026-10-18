package com.music.bitchord.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.pow

internal object PlaybackParametersMath {
    const val MIN_PITCH_SEMITONES = -6
    const val MAX_PITCH_SEMITONES = 6

    fun clampPitchSemitones(semitones: Int): Int =
        semitones.coerceIn(MIN_PITCH_SEMITONES, MAX_PITCH_SEMITONES)

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
