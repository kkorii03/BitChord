package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.StreamChoice
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * [StreamChoice.forgetUrl] for the rate-limited path: drop the pinned choice
 * whose stream URL matches [url], and say so when none does.
 *
 * Kept here while the upstream StreamChoice file is the v1.9 one; the warning
 * is the fork's distinction between "the pin survives under a different
 * spelling" and "there was no pin to drop".
 */
internal fun StreamChoice.forgetUrlKo(url: String) {
    val key = url.toHttpUrlOrNull()?.toString() ?: url
    var matched = false
    forgetWhere(emptySet()) { stream ->
        val same = (stream.url.toHttpUrlOrNull()?.toString() ?: stream.url) == key
        if (same) matched = true
        same
    }
    if (!matched) {
        TrackLog.w("BitChord", "no stream choice to forget for rate-limited URL: $url")
    }
}

private fun rawSetting(key: String, fallback: Boolean): Boolean =
    (AppSettings.transferablePrefs()[key] as? Boolean) ?: fallback

/** The fork's stored switches, read straight from the settings file. */
internal val AppSettings.preferMusicOnlyKo: Boolean
    get() = rawSetting("prefer_music_only", true)

internal val AppSettings.skipNonMusicPartsKo: Boolean
    get() = rawSetting("skip_non_music_parts", true)

internal val AppSettings.skipSponsorPartsKo: Boolean
    get() = rawSetting("skip_sponsor_parts", false)
