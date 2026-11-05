package com.music.bitchord.playback

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The songs music-only already refused, so a queue that cycles back to one does
 * not skip it a second time — see
 * [com.music.bitchord.data.net.SongVersionPolicy.skipsOfficialMvWithoutAudio].
 *
 * The verdict belongs to the queue it was taken in. A new queue and a music-only
 * change forget it: the same video may be the row that must play now, and a set
 * nothing ever clears only grew for the rest of the session.
 */
internal class SkippedOfficialMv {

    private val ids: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun contains(videoId: String): Boolean = videoId in ids

    fun mark(videoId: String) {
        ids.add(videoId)
    }

    fun clear() {
        ids.clear()
    }
}
