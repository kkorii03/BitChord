package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.TrackMatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The small pieces of the fork's search behaviour that are kept around the
 * v1.9 search core: source health, the search memo, the original-recording
 * filter, the hq-search line and the manual lookup window.
 *
 * Everything here is event-driven. Nothing starts a timer or a loop; the
 * cooldown is a timestamp comparison made on the next request.
 */
object SourceSearchKo {

    private const val TAG = "BitChord"

    /**
     * Sources that have failed repeatedly, and when they may be asked again.
     *
     * A dead addon must not cost every track in the queue its full timeout.
     * The rest is a timestamp comparison rather than a timer: nothing runs in
     * the background, and the source simply becomes eligible again on the
     * first request after [COOLDOWN_MS].
     */
    internal object SourceCooldown {
        private class State(var failures: Int = 0, var untilMs: Long = 0L)

        private val states = ConcurrentHashMap<String, State>()

        /**
         * A monotonic mark for each source's failures, so a caller can tell
         * whether *its* search failed without the source having to throw.
         */
        private val failureMarks = ConcurrentHashMap<String, AtomicLong>()

        internal fun failureMark(configId: String): Long =
            failureMarks[configId]?.get() ?: 0L

        fun isResting(configId: String): Boolean {
            val state = states[configId] ?: return false
            if (state.untilMs > System.currentTimeMillis()) return true
            if (state.untilMs != 0L) states.remove(configId)
            return false
        }

        /**
         * A monotonic mark for credential refusals, kept apart from the
         * generic failure mark so a search can say *why* it produced nothing.
         */
        private val authMarks = ConcurrentHashMap<String, AtomicLong>()

        internal fun authMark(configId: String): Long =
            authMarks[configId]?.get() ?: 0L

        fun failed(configId: String) {
            recordFailure(configId, countForRest = true)
        }

        /**
         * A transport fault. The mark is always raised so the empty answer is
         * not memoised; it only counts toward the rest while the device is
         * online, because an offline device fails every source at once.
         */
        internal fun networkFailed(configId: String, online: Boolean) {
            recordFailure(configId, countForRest = online)
        }

        /** The addon answered 401/402/403: its credential is refused. */
        internal fun authFailed(configId: String) {
            authMarks.computeIfAbsent(configId) { AtomicLong() }.incrementAndGet()
            recordFailure(configId, countForRest = true)
        }

        private fun recordFailure(configId: String, countForRest: Boolean) {
            failureMarks.computeIfAbsent(configId) { AtomicLong() }.incrementAndGet()
            if (!countForRest) return
            val state = states.computeIfAbsent(configId) { State() }
            state.failures += 1
            if (state.failures >= FAILURES_BEFORE_REST) {
                state.untilMs = System.currentTimeMillis() + COOLDOWN_MS
                TrackLog.w(
                    "BitChord",
                    "source $configId failed ${state.failures} times; resting for ${COOLDOWN_MS / 60_000} minutes",
                )
            }
        }

        fun succeeded(configId: String) {
            states.remove(configId)
        }

        internal fun resetForTest() {
            states.clear()
            failureMarks.clear()
            authMarks.clear()
        }

        private const val FAILURES_BEFORE_REST = 2
        private const val COOLDOWN_MS = 30 * 60 * 1000L
    }

    private data class SearchKey(
        val sourceId: String,
        val target: String,
        val request: String,
        val waitForAll: Boolean,
        val query: String,
    )

    private class SearchAnswer(val rows: List<Song>, val at: Long)

    private val searchAnswers = ConcurrentHashMap<SearchKey, SearchAnswer>()

    private const val SEARCH_MEMO_TTL_MS = 10 * 60 * 1000L
    private const val SEARCH_MEMO_MAX = 256

    private const val MANUAL_SOURCE_LOOKUP_TIMEOUT_MS = 12_000L

    /**
     * The failure and auth marks in hand before one search, so the answer can
     * be judged against whether this source reported a fault while producing
     * it.
     */
    class SearchGuard internal constructor(
        val configId: String,
        val failureMark: Long,
        val authMark: Long,
    )

    fun beginSearch(configId: String): SearchGuard = SearchGuard(
        configId,
        SourceCooldown.failureMark(configId),
        SourceCooldown.authMark(configId),
    )

    /** A hand-made request deliberately ignores the memo. */
    fun cachedRows(
        sourceId: String,
        targetKey: String,
        requestKey: String,
        waitForAll: Boolean,
        query: String,
        manual: Boolean,
    ): List<Song>? {
        if (manual) return null
        val key = SearchKey(sourceId, targetKey, requestKey, waitForAll, query)
        val now = System.currentTimeMillis()
        return searchAnswers[key]?.takeIf { now - it.at < SEARCH_MEMO_TTL_MS }?.rows
    }

    /**
     * Remembers [rows] only when neither mark moved during the call. A failure
     * mixed into an empty answer is not a confirmed miss, so that answer must
     * be asked again rather than reused for the memo's lifetime.
     */
    fun rememberRows(
        guard: SearchGuard,
        targetKey: String,
        requestKey: String,
        waitForAll: Boolean,
        query: String,
        rows: List<Song>,
    ): Boolean {
        val conclusive = !failureChanged(guard) && !authChanged(guard)
        if (conclusive) {
            if (searchAnswers.size >= SEARCH_MEMO_MAX) searchAnswers.clear()
            searchAnswers[SearchKey(guard.configId, targetKey, requestKey, waitForAll, query)] =
                SearchAnswer(rows, System.currentTimeMillis())
        }
        return conclusive
    }

    fun failureChanged(guard: SearchGuard): Boolean =
        SourceCooldown.failureMark(guard.configId) != guard.failureMark

    fun authChanged(guard: SearchGuard): Boolean =
        SourceCooldown.authMark(guard.configId) != guard.authMark

    fun verdictAfterFailure(guard: SearchGuard): String =
        if (authChanged(guard)) "auth" else "error"

    fun clearSearchAnswers() {
        searchAnswers.clear()
    }

    fun noteTimeout(configId: String) {
        SourceCooldown.failed(configId)
    }

    fun noteSucceeded(configId: String) {
        SourceCooldown.succeeded(configId)
    }

    fun noteThrown(configId: String, network: Boolean, online: Boolean) {
        if (network) SourceCooldown.networkFailed(configId, online) else SourceCooldown.failed(configId)
    }

    /** Drops resting sources unless the listener asked by hand. */
    fun withoutResting(
        sources: List<MusicSource>,
        manual: Boolean,
        target: TrackMatcher.Target,
        report: SourceResolver.SearchReport?,
    ): List<MusicSource> = sources.filterNot { source ->
        val resting = SourceCooldown.isResting(source.configId)
        if (resting && !manual) {
            TrackLog.d(
                TAG,
                "${source.displayName} is resting after repeated failures; skipping for this track",
            )
            val skipped = SourceResolver.SearchOutcome(verdict = "resting", conclusive = false)
            report?.record(skipped)
            TrackLog.d(TAG, hqSearchLine(target, source, skipped))
            true
        } else {
            if (resting) {
                TrackLog.d(
                    TAG,
                    "${source.displayName} is resting; asking it once because this upgrade was requested by hand",
                )
            }
            false
        }
    }

    fun originalOnly(
        rows: List<Song>,
        target: TrackMatcher.Target,
        strictOriginal: Boolean,
    ): List<Song> =
        if (strictOriginal) rows.filter { TrackMatcher.isOriginalRecording(it, target) } else rows

    /** The one line featurehealth parses: verdict and reason for one source. */
    fun hqSearchLine(
        target: TrackMatcher.Target,
        source: MusicSource,
        outcome: SourceResolver.SearchOutcome,
    ): String {
        val best = outcome.best?.let {
            "${it.title}/${it.artist}/${it.durationText ?: "?"}"
        } ?: "-"
        return "hq-search: '${target.title}' ${source.displayName} " +
            "results=${outcome.results} best=$best verdict=${outcome.verdict}"
    }

    fun sourceLookupTimeoutMs(manual: Boolean, configured: Long): Long =
        if (manual) MANUAL_SOURCE_LOOKUP_TIMEOUT_MS else configured
}
