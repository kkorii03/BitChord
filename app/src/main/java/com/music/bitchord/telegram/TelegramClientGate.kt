package com.music.bitchord.telegram

/**
 * When a Telegram TDLib client may stay open.
 *
 * The app has no startup connection: until something holds the client (the
 * Telegram settings screen in this stage, search or playback later) it stays
 * shut. Telegram-source playback is an independent reason to keep the client
 * open even in the background; otherwise a holder only counts while the app is
 * in the foreground. It shuts when neither reason remains, and opens again when
 * one returns. There are no timers here; every change is an event.
 *
 * [telegramPlaybackActive] must only be driven by Telegram playback. Local,
 * YouTube or add-on playback never counts: this stage has no Telegram playback
 * yet, so it stays false until that source starts.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later), rewritten
 * as an event-driven policy for BitChord. Kept pure so it is testable without
 * Android or TDLib.
 */
class TelegramClientGate {
    private val holders = LinkedHashSet<String>()

    var appForeground: Boolean = false
        private set

    var telegramPlaybackActive: Boolean = false
        private set

    val shouldBeOpen: Boolean
        get() = telegramPlaybackActive || (holders.isNotEmpty() && appForeground)

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

    fun onTelegramPlaybackActive(value: Boolean): Boolean {
        telegramPlaybackActive = value
        return shouldBeOpen
    }
}
