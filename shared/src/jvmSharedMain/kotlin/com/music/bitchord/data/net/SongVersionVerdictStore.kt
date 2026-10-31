package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song
import java.util.concurrent.ConcurrentHashMap

/**
 * The per-run bookkeeping behind the song-version lookup: found recordings,
 * confirmed misses, and the provisional answers that must not become either.
 *
 * A lookup can outlive the caller that started it. Its answer then arrives
 * after a deadline or a cancellation, and it is not a verdict: it may be a miss
 * that landed late or a request that was cut short. [waived] marks exactly
 * those ids so [store] refuses to freeze a provisional miss. Only a search
 * that ran to completion may be cached, and only a found recording is trusted
 * even when the caller stopped waiting.
 */
internal class SongVersionVerdictStore {

    /** What a stored miss means to a caller that is about to ask again. */
    internal enum class Miss {
        /** Nothing is stored for this id. */
        NONE,

        /** A completed search established that there is no song version. */
        SETTLED,

        /** A caller gave up before the answer; the miss must be looked up again. */
        RETRY,
    }

    private val cache = ConcurrentHashMap<String, Song>()
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val waived: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val retrySuggested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lock = Any()

    fun cached(videoId: String): Song? = cache[videoId]

    /** True when this run has a settled "no song version" verdict. */
    fun isMissing(videoId: String): Boolean =
        videoId in missing && videoId !in waived

    /** True when a cut-short lookup still owes its one post-audio retry. */
    fun isRetrySuggested(videoId: String): Boolean = videoId in retrySuggested

    /** A cover's upload is the chosen performance; it can never have a version. */
    fun markMissing(videoId: String) {
        missing.add(videoId)
    }

    /** Forgets the provisional miss left by the lookup a retry replaces. */
    fun beginRetry(videoId: String) {
        synchronized(lock) {
            missing.remove(videoId)
            retrySuggested.remove(videoId)
        }
    }

    /**
     * Starts the retry's own search after the cut-short lookup has finished.
     * The waiver belonged to that lookup; this search is the verdict-bearing
     * one, so it must not inherit it.
     */
    fun beginFreshSearch(videoId: String) {
        synchronized(lock) {
            missing.remove(videoId)
            waived.remove(videoId)
            retrySuggested.remove(videoId)
        }
    }

    /**
     * How an existing miss should be read now.
     *
     * A miss left by a caller that gave up is reopened (and its waiver
     * consumed) so the same video is revalidated once instead of frozen for
     * the run. A settled miss is the search's real answer.
     */
    fun settleMiss(videoId: String): Miss {
        if (videoId !in missing) return Miss.NONE
        synchronized(lock) {
            if (videoId in waived) {
                missing.remove(videoId)
                waived.remove(videoId)
                return Miss.RETRY
            }
        }
        return Miss.SETTLED
    }

    /** True when a lookup was cut short before its answer landed. */
    fun waitedOut(videoId: String): Boolean = synchronized(lock) { videoId in waived }

    /**
     * The waiter stopped on its own deadline or was cancelled.
     *
     * An answer that landed anyway was already stored; its miss is reopened so
     * it is revalidated. One that has not landed is waived, so [store] cannot
     * freeze its provisional result.
     *
     * @return true when a stored miss was reopened, so the caller can drop a
     *   provisional audio-version entry as well.
     */
    fun onWaiterCancelled(videoId: String, answerCompleted: Boolean): Boolean {
        synchronized(lock) {
            if (answerCompleted) {
                if (cache[videoId] == null && missing.remove(videoId)) return true
            } else {
                waived.add(videoId)
            }
            return false
        }
    }

    /** Keeps the retry marker in step with one answer, definitive or not. */
    fun recordAnswer(videoId: String, song: Song?, definitive: Boolean) {
        synchronized(lock) {
            if (definitive || song != null) {
                retrySuggested.remove(videoId)
            } else {
                waived.remove(videoId)
                retrySuggested.add(videoId)
            }
        }
    }

    /**
     * Stores one verdict. A non-definitive miss is never cached, and its
     * waiver is consumed once the cut-short lookup has finished.
     *
     * @return true when a provisional miss has to be discarded from the
     *   caller's other cache as well.
     */
    fun store(videoId: String, song: Song?, definitive: Boolean): Boolean {
        synchronized(lock) {
            // Read under the same lock as the write: a waiter that gave up
            // between the caller's own check and this call has still made the
            // miss provisional, and a miss written here would never be retried.
            val waivedNow = waived.remove(videoId)
            if (definitive && !(song == null && waivedNow)) {
                if (song == null) missing.add(videoId) else cache[videoId] = song
                return false
            }
            return song == null
        }
    }
}
