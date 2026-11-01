package com.music.bitchord.ko

import com.music.bitchord.playback.CrossfadeController

/**
 * [KO] crossfade-fade
 *
 * The production fader pair with the transition-only loudness match, and the
 * per-quarter gain samples kept for the end log.
 */
internal fun CrossfadeController.koFadeGainsAt(progress: Float): KoCrossfadeGain.Gains =
    KoCrossfadeGain.Gains(
        incoming = (KoCrossfadeGain.mixRise(progress) * koMatchGain(render.incomingGain, progress)).coerceIn(0f, 1f),
        outgoing = (KoCrossfadeGain.mixFall(progress) * koMatchGain(render.outgoingGain, progress)).coerceIn(0f, 1f),
    )

internal fun CrossfadeController.koMatchGain(planned: Double, progress: Float): Float {
    val weight = ((1f - progress) / CrossfadeController.LOUDNESS_MATCH_RELEASE).coerceIn(0f, 1f).toDouble()
    return (1.0 + (planned - 1.0) * weight).toFloat().coerceIn(0f, 1f)
}

/**
 * Records each quarter at the moment the fade crosses it, using the same fader
 * functions playback uses. A skipped tick therefore still reports the gain at
 * 25/50/75 rather than whatever the next tick happened to be.
 */
internal fun CrossfadeController.koLogFadeGains(progress: Float) {
    if (progress <= ko.lastFadeProgress) return
    ko.lastFadeProgress = progress
    while (ko.lastFadeQuarter < 3 && progress >= (ko.lastFadeQuarter + 1) * 0.25f) {
        val quarter = ko.lastFadeQuarter + 1
        val gains = koFadeGainsAt(quarter * 0.25f)
        ko.fadeQuarterGains[quarter - 1] =
            "${quarter * 25}%=${KoCrossfadeLog.gainText(gains.incoming)}/${KoCrossfadeLog.gainText(gains.outgoing)}"
        ko.lastFadeQuarter = quarter
    }
}
