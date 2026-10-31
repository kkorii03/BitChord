package com.music.bitchord.telegram

/**
 * When a Telegram TDLib client may stay open.
 *
 * The app has no startup connection: until something holds the client (the
 * Telegram settings screen in this stage, search or playback later) it stays
 * shut. It also shuts when the app leaves the foreground with no playback,
 * and opens again if a holder remains or playback is what kept it alive. There
 * are no timers here; every change is an event.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later), rewritten
 * as an event-driven policy for BitChord. Kept pure so it is testable without
 * Android or TDLib.
 */
class TelegramClientGate {
    private val holders = LinkedHashSet<String>()

    var appForeground: Boolean = false
        private set

    var playbackActive: Boolean = false
        private set

    val shouldBeOpen: Boolean
        get() = holders.isNotEmpty() && (appForeground || playbackActive)

    val openReasons: List<String> get() = holders.toList()

    fun acquire(reason: String): Boolean {
        holders += reason
        return shouldBeOpen
    }

    fun release(reason: String): Boolean {
        holders -= reason
        return shouldBeOpen
    }

    fun onForeground(value: Boolean): Boolean {
        appForeground = value
        return shouldBeOpen
    }

    fun onPlaybackActive(value: Boolean): Boolean {
        playbackActive = value
        return shouldBeOpen
    }
}
