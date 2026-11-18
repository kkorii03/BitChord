package com.music.bitchord.ko

/**
 * [KO] cold2: splits `TIMING first audio` at the play press.
 *
 * Phone 11-18 17:11: a restored queue stamps "track selected" when it is
 * installed (no prepare, no resolve: battery), and the listener pressed play
 * 1.6 s later. The line read 8364 ms; 1.6 s of it was the listener's own time,
 * and the app's wait was 6.8 s. The press is now named on the same line, so a
 * cold start says which part was the app.
 */
internal object KoFirstAudioSplit {

    /** Below this the press is part of the selection itself (a tap on a row). */
    const val MIN_GAP_MS = 300L

    @Volatile
    private var press: Pair<Long, Long>? = null

    /** The player was told to play while the selection stamped at [selectedAt] had not sounded. */
    fun pressed(selectedAt: Long?, nowMs: Long) {
        selectedAt ?: return
        // The first press after the selection is the one the listener waited from.
        if (press?.first == selectedAt) return
        press = selectedAt to nowMs
    }

    /** The suffix for the first-audio line of [selectedAt]: empty unless the press came well after it. */
    fun suffix(selectedAt: Long, nowMs: Long): String {
        val (forSelection, at) = press ?: return ""
        if (forSelection != selectedAt || at - selectedAt < MIN_GAP_MS || at > nowMs) return ""
        return " (${nowMs - at}ms since play pressed ${at - selectedAt}ms after selection)"
    }

    internal fun resetForTest() {
        press = null
    }
}
