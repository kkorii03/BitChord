package com.music.bitchord.ko

import android.os.SystemClock
import com.music.bitchord.playback.AudioCache

/**
 * Keeps the audio a lyrics-timing measurement still needs.
 *
 * The cache sweep otherwise drops an analysis-only copy as soon as playback has
 * moved on; a lyrics pass can be sitting on that copy waiting for the original
 * (or the next candidate) to download. Pinning the video id for a short while
 * lets the measurement finish without holding files forever.
 */
private object TimingKeep {
    private const val KEEP_MS = 15 * 60_000L
    private const val KEEP_MAX = 16

    private val kept = LinkedHashMap<String, Long>()

    fun keep(videoId: String) = synchronized(kept) {
        kept.remove(videoId)
        kept[videoId] = SystemClock.elapsedRealtime()
        while (kept.size > KEEP_MAX) kept.remove(kept.keys.first())
    }

    fun isKept(videoId: String): Boolean = synchronized(kept) {
        val at = kept[videoId] ?: return false
        SystemClock.elapsedRealtime() - at < KEEP_MS
    }
}

/** [AudioCache] hook for the lyrics-timing pin; see [TimingKeep]. */
fun AudioCache.keepForTiming(videoId: String) {
    TimingKeep.keep(videoId)
}

/** Whether [videoId] is still pinned for a lyrics-timing measurement. */
fun AudioCache.keptForTiming(videoId: String): Boolean = TimingKeep.isKept(videoId)
