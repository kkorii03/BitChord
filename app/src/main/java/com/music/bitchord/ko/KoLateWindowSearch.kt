package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream

/**
 * The window's addon search for the next track, run on past its 4 s cap.
 *
 * Phone 11-19 (vc215, LTE): vampire aHmg0jsmNhg's window opened at 02:08:07,
 * the addon's search took 2.0 s and its `/stream` answered 200 in 1.5 s, but on
 * the phone that crossed [SOURCE_LOOKUP_TIMEOUT_MS] ("timed out after 4000ms;
 * skipping for this track" at 02:08:11). The timeout cancelled the walk, so the
 * lookup ended with nothing and no proof ever started: the song started on
 * YouTube and the late-proof path ([KoLateDirectProof], the second look) was
 * handed a finished, empty lookup. STORM II at 01:54 was the same.
 *
 * The standby's wait is unchanged: its waiters still take the 4 s-capped
 * answer and fall back to YouTube as before. Only the lookup handed on after
 * them searches again, once, with the lead's budget
 * ([KoDirectLead.LEAD_SOURCE_TIMEOUT_MS]) and the same proof, and only when an
 * addon failed during the window's search (a timeout; a miss or a refused row
 * answers from the search memo and the verdict store and is not asked again)
 * and the track is still next or playing. No timer: it runs in the lookup's
 * own coroutine, and the addon's cache usually holds the answer by then.
 */
internal object KoLateWindowSearch {

    /** Each direct addon's failure mark, read before the window's search. */
    fun marks(): Map<String, Long> =
        SourceResolver.directAddons().associate { it.configId to SourceResolver.SourceCooldown.failureMark(it.configId) }

    /** Whether an addon failed since [before] (the window's timeout) and is not resting for it. */
    fun timedOut(before: Map<String, Long>): Boolean = before.any { (id, mark) ->
        SourceResolver.SourceCooldown.failureMark(id) != mark && !SourceResolver.SourceCooldown.isResting(id)
    }

    /** The search on, after the window's answer was nothing; null when nothing timed out or the track moved on. */
    suspend fun after(
        videoId: String,
        before: Map<String, Long>,
        stillWanted: () -> Boolean,
        search: suspend () -> SourceStream?,
    ): SourceStream? {
        if (!timedOut(before) || !stillWanted()) return null
        TrackLog.d(TAG, "direct window: $videoId addon ran past ${SOURCE_LOOKUP_TIMEOUT_MS}ms; searching on for the late proof", about = videoId)
        val found = search()
        TrackLog.d(
            TAG,
            "direct window: $videoId late search ${found?.let { "proved ${it.format.summary}" } ?: "found nothing"}",
            about = videoId,
        )
        return found
    }

    private const val TAG = "BitChord"
}
