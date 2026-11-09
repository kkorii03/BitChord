package com.music.bitchord.ko

import android.media.AudioDeviceInfo
import android.os.Build
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioRouting
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Makes the lyric offset follow the audio output. [AppSettings.lyricsOffsetMs]
 * stays the one value everything reads; this decides which stored offset it
 * holds. The built-in speaker's offset is the old global preference, every
 * other output's lives in [KoLyricsRouteKey.PREF_KEY].
 *
 * Event-driven only: [onRoute] is called from the playback service's output
 * reconfiguration, which the framework's device callback already triggers.
 */
internal object KoLyricsRouteOffset {
    private const val TAG = "BitChord"

    /** The caption for the offset control, or null on the built-in speaker. */
    val routeLabel = MutableStateFlow<String?>(null)

    private var routeKey: String? = null

    /** Called whenever the active output is resolved. Does nothing unless the output changed. */
    @Synchronized
    fun onRoute(device: AudioDeviceInfo?, kind: AudioRouting.Kind) {
        val tag = kind.name.lowercase()
        val name = device?.productName?.toString()?.trim()
        val address = if (device != null && Build.VERSION.SDK_INT >= 28) device.address else null
        val key = KoLyricsRouteKey.routeKey(tag, address, name)
        if (key == routeKey) return
        routeKey = key
        routeLabel.value = KoLyricsRouteKey.label(key, tag, name)
        if (!AppSettings.prefsReady()) return
        val offset = offsetOf(key)
        AppSettings.lyricsOffsetMs.value = offset
        // Kind and name only: logs are shared, a Bluetooth address is not needed to read them.
        TrackLog.i(TAG, "lyrics offset route=$tag:${name ?: "-"} offset=$offset")
    }

    /**
     * Saves [normalized] under the current route. False means the route is the
     * built-in speaker, and the caller keeps writing the global preference.
     */
    @Synchronized
    fun saveForRoute(normalized: Int): Boolean {
        val key = routeKey ?: return false
        if (key == KoLyricsRouteKey.DEFAULT_KEY) return false
        val prefs = AppSettings.prefs
        val map = KoLyricsRouteKey.parse(prefs.getString(KoLyricsRouteKey.PREF_KEY, null))
        prefs.edit()
            .putString(KoLyricsRouteKey.PREF_KEY, KoLyricsRouteKey.encode(KoLyricsRouteKey.withOffset(map, key, normalized)))
            .apply()
        return true
    }

    /** After the preferences were (re)read, e.g. a restore: put the current route's offset back on screen. */
    @Synchronized
    fun afterLoad() {
        val key = routeKey ?: return
        if (key == KoLyricsRouteKey.DEFAULT_KEY) return
        AppSettings.lyricsOffsetMs.value = offsetOf(key)
    }

    /** The speaker's offset as stored, whichever route is showing: what a backup carries under the old key. */
    fun speakerOffset(): Int =
        AppSettings.prefs.getInt(KEY_LYRICS_OFFSET_MS, 0)
            .coerceIn(KoLyricsRouteKey.MIN_OFFSET_MS, KoLyricsRouteKey.MAX_OFFSET_MS)

    private fun offsetOf(key: String): Int =
        if (key == KoLyricsRouteKey.DEFAULT_KEY) {
            speakerOffset()
        } else {
            KoLyricsRouteKey.offsetFor(
                KoLyricsRouteKey.parse(AppSettings.prefs.getString(KoLyricsRouteKey.PREF_KEY, null)),
                key,
            )
        }
}
