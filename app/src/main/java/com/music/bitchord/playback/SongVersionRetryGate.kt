package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy

/**
 * The once-per-track cap on the post-first-sound song-version retry.
 *
 * A before-audio lookup that gave up on its deadline or was cancelled is owed
 * exactly one retry. The row stored here is the *lookup* row, not the raw queue
 * item: it may carry the synthesis a download needed (`typeUnknown`, no local
 * file), and re-deriving it from the MediaItem would make the retry answer null
 * immediately while still spending its only chance.
 */
internal class SongVersionRetryGate(private val maxTracks: Int = 64) {

    private val pending = LinkedHashMap<String, Song>()
    private val retried = object : LinkedHashMap<String, Boolean>(maxTracks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
            size > maxTracks
    }
    private val lock = Any()

    /** Notes that [song]'s before-audio lookup was cut short and owes a retry. */
    fun markPending(song: Song) {
        val videoId = song.videoId
        if (videoId.isBlank()) return
        synchronized(lock) {
            if (!SongVersionPolicy.shouldRetrySongVersionAfterAudio(
                    timedOutOrCancelled = true,
                    alreadyRetried = videoId in retried,
                )
            ) {
                return
            }
            pending[videoId] = song
        }
    }

    /**
     * Spends the one retry for [videoId] and returns the lookup row, or null
     * when none is pending or the retry has already been spent.
     */
    fun takePending(videoId: String): Song? {
        synchronized(lock) {
            val song = pending[videoId] ?: return null
            if (!SongVersionPolicy.shouldRetrySongVersionAfterAudio(
                    timedOutOrCancelled = true,
                    alreadyRetried = videoId in retried,
                )
            ) {
                pending.remove(videoId)
                return null
            }
            pending.remove(videoId)
            retried[videoId] = true
            return song
        }
    }

    /** True when [videoId] still owes its retry. */
    fun isPending(videoId: String): Boolean = synchronized(lock) { videoId in pending }
}
