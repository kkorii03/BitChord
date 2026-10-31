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
 * [telegramPlaybackActive] must only be driven by Telegram playback (including
 * preparing a Telegram stream). Local, YouTube or add-on playback never counts.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later), rewritten
 * as an event-driven policy for BitChord. Kept pure so it is testable without
 * Android or TDLib.
 */
class TelegramClientGate {
    private val holders = LinkedHashSet<String>()
    private var streamLeases = 0

    var appForeground: Boolean = false
        private set

    var telegramPlaybackActive: Boolean = false
        private set

    val shouldBeOpen: Boolean
        get() = telegramPlaybackActive || (holders.isNotEmpty() && appForeground)

    val openReasons: List<String> get() = holders.toList()

    /**
     * A stream (or the metadata lookup that prepares one) needs the client.
     *
     * This is the same reason as playback, so it opens and stays open even in
     * the background. Leases are counted: resolving metadata for a queue
     * transition must not close the client under a stream that is already
     * reading, and the last release is what lets it close.
     *
     * @return true when this was the first outstanding lease.
     */
    fun beginStreamLease(): Boolean {
        val first = streamLeases == 0
        streamLeases++
        telegramPlaybackActive = true
        return first
    }

    /**
     * Releases one [beginStreamLease].
     *
     * @return true when this was the last outstanding lease.
     */
    fun endStreamLease(): Boolean {
        if (streamLeases > 0) streamLeases--
        val last = streamLeases == 0
        if (last) telegramPlaybackActive = false
        return last
    }

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
