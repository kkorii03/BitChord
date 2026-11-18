package com.music.bitchord.ko

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.playback.BatteryPolicy

/**
 * Whether next-track high-quality work (the direct lead, hq-ahead, a lossless
 * pin's read-ahead bytes, download-check ahead) may spend bytes now.
 *
 * Phone 11-19 (vc215, LTE, unlimited plan, cellular quality Best): the lead
 * never ran once, because a metered connection stopped it whatever the
 * listener chose, while the song itself was then fetched as a 24-bit FLAC over
 * the same LTE when it played. On a metered network this work now follows the
 * listener's own ceiling for it: lossless or better asked for those bytes
 * anyway; below lossless it stays off as before. Saving power stops it exactly
 * as [BatteryPolicy.cachePrefetchBytesAllowed] always did.
 */
internal object KoHiResPrefetch {

    /** [BatteryPolicy.cachePrefetchBytesAllowed], with a metered network let through at a lossless ceiling. */
    fun allowed(metered: Boolean, lowPower: Boolean): Boolean =
        BatteryPolicy.cachePrefetchBytesAllowed(metered = metered && !losslessOn(metered), lowPower = lowPower)

    /** Whether the ceiling for a network that is [metered] (or not) is lossless or better. */
    internal fun losslessOn(metered: Boolean): Boolean = AppSettings.ceilingFor(
        wifi = AppSettings.audioQualityWifi.value,
        cellular = AppSettings.audioQualityCellular.value,
        metered = metered,
        override = AppSettings.streamCeilingOverride.value,
    ) >= AudioQuality.LOSSLESS
}
