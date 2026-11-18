package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog

/**
 * How long the next track's addon walk took to start after its window opened.
 *
 * Phone 11-19 02:30 (vc215, LTE): Dance The Night Z1yDjhXIDSc's window opened
 * at 02:30:19.641 and the walk's first step (the addon manifest) came at
 * 02:30:27.108, 7.5 s later, with no app line in between; its 4 s per-addon cap
 * then ran out at 02:30:31.109, exactly 4.0 s after that first step, so the
 * seconds went before the walk, not inside it. DlFXDl_ROAM (02:23) lost 6.0 s
 * the same way; aHmg0jsmNhg and 9ssQKlLxBdQ lost 3 ms. The code between the
 * two (the read-ahead's launch, the gate's start, the lookup's own coroutine,
 * the addon list) waits on no network and no lock, so this names the step the
 * time goes to on the phone: one line per window, only the first lookup's steps.
 *
 * Event-driven: the window's own opening, the steps the lookup already runs,
 * and one line when the search starts. No timer.
 */
internal object KoWindowClock {

    private class Opened(val id: String, val atNs: Long) {
        val steps = StringBuilder()

        @Volatile
        var told = false
    }

    @Volatile
    private var opened: Opened? = null

    /** The window opened on [videoId] (DirectSourceGate.openNext). */
    fun opened(videoId: String) {
        opened = Opened(videoId, System.nanoTime())
    }

    /** [videoId]'s lookup reached [step]; kept once per step, until the walk starts. */
    fun step(videoId: String, step: String) {
        val open = opened?.takeIf { it.id == videoId && !it.told } ?: return
        synchronized(open) {
            if (" $step +" in open.steps) return
            open.steps.append(" $step +").append(msSince(open)).append("ms")
        }
    }

    /** [videoId]'s addon search is about to start: the line, once per window. */
    fun walkStarted(videoId: String?) {
        val open = opened?.takeIf { it.id == videoId && !it.told } ?: return
        val steps = synchronized(open) {
            if (open.told) return
            open.told = true
            open.steps.toString().trim()
        }
        TrackLog.d(
            "BitChord",
            "direct window: ${open.id} walk started +${msSince(open)}ms after open" +
                if (steps.isNotEmpty()) " ($steps)" else "",
            about = open.id,
        )
    }

    private fun msSince(open: Opened) = (System.nanoTime() - open.atNs) / 1_000_000
}
