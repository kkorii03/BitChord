package com.music.bitchord.playback

import android.content.Context
import android.os.PowerManager

/**
 * Whether the screen is interactive, as the playback service sees it.
 *
 * The service already receives [android.content.Intent.ACTION_SCREEN_ON] and
 * [android.content.Intent.ACTION_SCREEN_OFF] for its lyric ticker; this is the
 * same signal, kept in one place for the work that must not run with nobody
 * looking at it. Reading [PowerManager.isInteractive] directly is the initial
 * value only: a binder call on every crossfade tick would be its own cost, and
 * the broadcasts are already arriving.
 *
 * Playback itself is never gated on this. A decoder that is making sound keeps
 * running; only work that exists for someone looking at the screen — verification,
 * lyric timing, video audio and silent auditions — reads it. Automix analysis is
 * deliberately not gated here: it has to be ready when the next track arrives.
 */
internal object PowerState {

    @Volatile
    var screenOn: Boolean = true
        private set

    /** Reads the current state once, for a process that started with the screen off. */
    fun initialize(context: Context) {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        update(power?.isInteractive != false)
    }

    fun update(value: Boolean) {
        if (screenOn == value) return
        screenOn = value
    }
}
