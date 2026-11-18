package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.NextPrepPolicy

/**
 * [KO] log1819: the next row's paid sync waits for a player message at 30 s of the
 * current song. A version swap of the current song (`hq-swap`, or `recording verified;
 * using the downloaded file`) moves it onto another player or item, and the message
 * posted on the old one never arrives: the wait ran out at its deadline and the next row
 * was never heard (11-18 Safe and Sound jWpZuFM0Qtk, Can't Hold Us 96L5sV7rqX0, WHAT A
 * LIFE bSzH1kdbf64: `sync=skipped(no-answer)`, then a whole song with no sync with the
 * screen off).
 *
 * At that deadline the gate's own question is asked once more of what plays now: the
 * same song, past 30 s. No new wait or timer; a skipped or paused song still keeps it shut.
 */
object KoAiGateAfterSwap {

    internal fun opens(startedId: String?, nowId: String?, nowPlayedMs: Long): Boolean =
        startedId != null && startedId == nowId && NextPrepPolicy.aiGateOpen(nowPlayedMs)

    /** [opens], logged when it is what opened the gate. */
    fun openAfterTimeout(startedId: String?, nowId: String?, nowPlayedMs: Long): Boolean {
        val open = opens(startedId, nowId, nowPlayedMs)
        if (open) {
            TrackLog.i("BitChord", "next-prep gate: open after the 30 s message was lost (swap) played=${nowPlayedMs}ms", startedId)
        }
        return open
    }
}
