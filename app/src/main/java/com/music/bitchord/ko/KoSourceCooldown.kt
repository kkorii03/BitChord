package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.playback.AudioCache
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the device itself has no usable connection. Overridable so the
 * offline half of the rest rule can be tested without an Android context.
 */
internal object KoSourceRuntime {
    internal var offlineCheck: () -> Boolean = { AudioCache.isOffline() }
}

/**
 * Sources that have failed repeatedly, and when they may be asked again.
 *
 * A dead addon must not cost every track in the queue its full timeout.
 * The rest is a timestamp comparison rather than a timer: nothing runs in the
 * background, and the source simply becomes eligible again on the
 * first request after [COOLDOWN_MS].
 */
internal object KoSourceCooldown {
    private class State(var failures: Int = 0, var untilMs: Long = 0L)

    private val states = ConcurrentHashMap<String, State>()

    /** When an upgrade walk last asked each resting source — see [takeRestProbe]. */
    private val restProbes = ConcurrentHashMap<String, Long>()

    /**
     * A monotonic mark for each source's failures, so a caller can tell
     * whether *its* search failed without the source having to throw.
     *
     * Some sources turn a transport fault into an empty answer (see
     * [MusicSource.search]), which is indistinguishable from "this
     * catalogue does not have that recording" at the call site. Comparing
     * this mark before and after the call is what keeps that empty answer
     * from being memoised as a confirmed miss.
     */
    private val failureMarks = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /**
     * A monotonic mark for credential refusals, kept apart from the
     * generic failure mark so a search can say *why* it produced nothing.
     * A 401/402/403 still raises [failureMarks] and follows the resting
     * rule; this mark only decides whether the verdict reads `auth`
     * instead of `error`.
     */
    private val authMarks = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    internal fun failureMark(configId: String): Long =
        failureMarks[configId]?.get() ?: 0L

    internal fun authMark(configId: String): Long =
        authMarks[configId]?.get() ?: 0L

    /**
     * Sources whose candidates could not be measured for several different
     * tracks in a row: every download refused (403/410) or only a preview.
     * Such a source answers searches fine, so the failure rule above never
     * rests it, yet every upgrade paid a search and a download for nothing
     * (the listener's Deezer addon 403ed on every track of 11-01). Memory
     * only; a measured verdict or a settings change forgets it.
     */
    private class Unmeasurable(val tracks: LinkedHashSet<String> = LinkedHashSet(), var untilMs: Long = 0L)

    private val unmeasurable = ConcurrentHashMap<String, Unmeasurable>()

    /** One candidate of [configId] could not be measured for [reason]. */
    internal fun unmeasured(
        configId: String,
        trackId: String,
        reason: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val state = unmeasurable.computeIfAbsent(configId) { Unmeasurable() }
        synchronized(state) {
            state.tracks.add(trackId)
            if (state.tracks.size >= UNMEASURABLE_TRACKS_BEFORE_REST && state.untilMs <= nowMs) {
                state.untilMs = nowMs + UNMEASURABLE_REST_MS
                TrackLog.w(
                    "BitChord",
                    "source $configId resting ${UNMEASURABLE_REST_MS / 3_600_000}h: " +
                        "unmeasurable x${state.tracks.size} ($reason)",
                )
            }
        }
    }

    /** A candidate of [configId] was downloaded and compared, whatever the verdict. */
    internal fun measured(configId: String) {
        unmeasurable.remove(configId)
    }

    /**
     * The songs in a row each source has answered with the wrong file.
     *
     * [KoRefusedRows] keeps a bad file by row because a catalogue files good and
     * bad copies side by side, and one bad file says nothing about the rest. But
     * a row memory cannot see a source whose files are all bad: phone 11-06,
     * LastWave matched 99 times and 90 of those were a lossy placeholder under a
     * FLAC claim, on 59 of 73 songs, each song paying for the lookup anew.
     */
    private val wrongFileSongs = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * [configId] answered [song] with the wrong file. Parked the same way as an
     * unmeasurable source, and for the same reason: its search answers fine, so
     * the failure rule never rests it and a probe would only be refused again.
     */
    internal fun wrongFile(configId: String, song: String, nowMs: Long = System.currentTimeMillis()) {
        var rest = false
        wrongFileSongs.compute(configId) { _, songs ->
            val seen = (songs ?: HashSet()).apply { add(song) }
            rest = seen.size >= WRONG_FILE_SONGS_BEFORE_REST
            seen.takeUnless { rest }
        }
        if (!rest) return
        val state = unmeasurable.computeIfAbsent(configId) { Unmeasurable() }
        synchronized(state) { state.untilMs = maxOf(state.untilMs, nowMs + WRONG_FILE_REST_MS) }
        TrackLog.w(
            "BitChord",
            "source $configId resting ${WRONG_FILE_REST_MS / 60_000}m: " +
                "wrong file on $WRONG_FILE_SONGS_BEFORE_REST songs in a row",
        )
    }

    /** A stream of [configId] was what it claimed: the bad files were not in a row after all. */
    internal fun rightFile(configId: String) {
        wrongFileSongs.remove(configId)
    }

    /**
     * Whether [configId] is parked for unmeasurable candidates. Unlike a
     * failure rest, a hand-made upgrade does not probe it: its search
     * would succeed and its download would be refused again.
     */
    internal fun isUnmeasurable(configId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val state = unmeasurable[configId] ?: return false
        synchronized(state) {
            if (state.untilMs == 0L) return false
            if (state.untilMs > nowMs) return true
            unmeasurable.remove(configId)
            return false
        }
    }

    fun isResting(configId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isUnmeasurable(configId, nowMs)) return true
        val state = states[configId] ?: return false
        if (state.untilMs > nowMs) return true
        if (state.untilMs != 0L) states.remove(configId)
        return false
    }

    /**
     * [restUntilMs] is the host's own rest expiry when there is one, so a
     * sleeping gateway that wakes in a minute is not written off for
     * [COOLDOWN_MS]. Zero keeps the ordinary cooldown, which is what a
     * transport fault or a plain 5xx still needs: the dead-addon rule
     * exists so one unreachable source cannot cost every track its window.
     */
    fun failed(configId: String, restUntilMs: Long = 0L) {
        recordFailure(configId, countForRest = true, restUntilMs = restUntilMs)
    }

    /**
     * A transport fault — DNS, TLS, a refused connection, a read timeout.
     *
     * The mark is always raised, because a source that swallowed this fault
     * and returned an empty answer has *not* said the catalogue lacks the
     * track. Whether it also counts toward the rest depends on the device:
     * when the whole device is offline (or behind a captive portal), every
     * source fails the same way at once, and parking them all for 30
     * minutes would outlast the outage. The mark still keeps that empty
     * answer from being memoised as final, so it is asked again on the
     * first request after the link returns.
     */
    internal fun networkFailed(configId: String, online: Boolean) {
        recordFailure(configId, countForRest = online)
    }

    /**
     * The addon answered 401/402/403: its credential or permission is
     * refused, not its catalogue empty and not its server down.
     *
     * Counts toward the rest rule exactly like any other source failure,
     * so the same refused source is not asked on every track. The separate
     * mark lets the search verdict say `auth` instead of the misleading
     * `title`, and keeps that empty answer out of the memo.
     */
    internal fun authFailed(configId: String) {
        authMarks.computeIfAbsent(configId) { java.util.concurrent.atomic.AtomicLong() }
            .incrementAndGet()
        recordFailure(configId, countForRest = true)
    }

    /**
     * A source did not answer inside its per-track window.
     *
     * Telegram is exempt: a cold TDLib session plus a search is slower than
     * the race's window by design, and the source has not answered "no" —
     * it has not finished. Counting that as a failure is what put a healthy
     * Telegram account into a thirty-minute rest after the listener added
     * more chats. Every other source keeps the old timeout rule.
     */
    internal fun timedOut(configId: String, kind: SourceKind, online: Boolean) {
        if (kind == SourceKind.TELEGRAM) return
        recordFailure(configId, countForRest = online)
    }

    /** Forgets a rest and its failure count after the source's settings changed. */
    internal fun clear(configId: String) {
        states.remove(configId)
        unmeasurable.remove(configId)
        wrongFileSongs.remove(configId)
        restProbes.remove(configId)
    }

    private fun recordFailure(
        configId: String,
        countForRest: Boolean,
        restUntilMs: Long = 0L,
    ) {
        failureMarks.computeIfAbsent(configId) { java.util.concurrent.atomic.AtomicLong() }
            .incrementAndGet()
        if (!countForRest) return
        val nowMs = System.currentTimeMillis()
        val state = states.computeIfAbsent(configId) { State() }
        state.failures += 1
        if (state.failures >= FAILURES_BEFORE_REST) {
            val untilMs = if (restUntilMs > nowMs) restUntilMs else nowMs + COOLDOWN_MS
            state.untilMs = maxOf(state.untilMs, untilMs)
            TrackLog.w(
                "BitChord",
                "source $configId failed ${state.failures} times; resting until $untilMs",
            )
        }
    }

    fun succeeded(configId: String) {
        states.remove(configId)
        restProbes.remove(configId)
    }

    /**
     * Whether an upgrade walk may ask this resting source now, and takes the
     * turn when it may.
     *
     * An upgrade re-search is allowed one short probe of a resting source, since
     * a parked source may be the one holding the FLAC. Given to every walk it
     * cancelled the rest: phone 11-06 asked a dead addon on all 72 upgrades, 54
     * failures in a row, each one spending its four second window, and kept
     * asking an addon that had said it was being rate limited. One walk per
     * [REST_PROBE_EVERY_MS] still finds a source that has come back.
     */
    internal fun takeRestProbe(configId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        var taken = false
        restProbes.compute(configId) { _, last ->
            if (last == null || nowMs - last >= REST_PROBE_EVERY_MS) {
                taken = true
                nowMs
            } else {
                last
            }
        }
        return taken
    }

    internal fun resetForTest() {
        states.clear()
        failureMarks.clear()
        authMarks.clear()
        unmeasurable.clear()
        wrongFileSongs.clear()
        restProbes.clear()
    }

    private const val WRONG_FILE_SONGS_BEFORE_REST = 3
    private const val WRONG_FILE_REST_MS = 30 * 60 * 1000L
    private const val REST_PROBE_EVERY_MS = 10 * 60 * 1000L
    private const val UNMEASURABLE_TRACKS_BEFORE_REST = 3
    private const val UNMEASURABLE_REST_MS = 6 * 60 * 60 * 1000L
    private const val FAILURES_BEFORE_REST = 2
    private const val COOLDOWN_MS = 30 * 60 * 1000L
}
