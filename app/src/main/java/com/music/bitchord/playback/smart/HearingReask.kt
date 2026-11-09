package com.music.bitchord.playback.smart

import com.music.bitchord.data.DiagnosticsState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One more ask for the playing row once the network is back, after its asks got no reply.
 *
 * A dropped connection gets one resend inside the ask ([SongHearing.groqWithResend],
 * Deepgram's own resends). A longer outage outlasts those, and nothing asked again
 * during the row: 11-09 loop rounds 210-214, 3 of 210 rows (5nxb792c-b4, NPZtKA6343o,
 * vRjfYdwM5L4) had every ask end with no reply 10-15 s in and then played 113-134 s
 * more with no sync at all, while the player and the next row's read-ahead were
 * downloading again 10-25 s after the failure.
 *
 * Arms on a no-reply for the row that is sounding, never when that row already got a
 * reply (an answer, a 429, a "heard nothing": the server is reachable, and its own
 * rules decide). Fires on a real event that says bytes are arriving again ([networkUp]:
 * another hearing request answered, or the player cache started a file), at least
 * [QUIET_MS] after the last failure so it does not land in the same outage. At most
 * once per row, and only for the row still sounding. No timer, no poll: an event that
 * never comes asks nothing.
 */
internal class HearingReask(
    private val sounding: () -> String?,
    private val rowSince: () -> Long,
    private val ask: (String) -> Unit,
) {
    private data class Row(val videoId: String, val since: Long)

    /** The row waiting for its one more ask. Read on every cache file start, so a volatile alone. */
    @Volatile
    private var pending: Row? = null
    private var failedAtMs = 0L

    /** The row that got a reply or had its one more ask: nothing more for it. */
    private var settled: Row? = null

    /** A no-reply for [videoId]. True when it armed the row's one more ask. */
    @Synchronized
    fun noReply(videoId: String, nowMs: Long): Boolean {
        val row = soundingRow(videoId) ?: return false
        if (row == settled) return false
        pending = row
        failedAtMs = nowMs
        return true
    }

    /** A server answered for [videoId]: its row needs no more asks from here. */
    @Synchronized
    fun replied(videoId: String) {
        val row = soundingRow(videoId) ?: return
        settled = row
        if (pending == row) pending = null
    }

    /** Bytes are arriving again. Asks the waiting row once when [decide] says so. */
    fun networkUp(nowMs: Long) {
        // The hot path: every cache file start lands here, almost always with nothing waiting.
        if (pending == null) return
        val askFor = synchronized(this) {
            val row = pending ?: return
            when (decide(row.videoId, sounding(), row.since, rowSince(), failedAtMs, nowMs)) {
                Step.WAIT -> return
                Step.DROP -> {
                    pending = null
                    return
                }
                Step.ASK -> {
                    pending = null
                    settled = row
                    row.videoId
                }
            }
        }
        ask(askFor)
    }

    private fun soundingRow(videoId: String): Row? =
        if (videoId == sounding()) Row(videoId, rowSince()) else null

    internal enum class Step { WAIT, DROP, ASK }

    companion object {
        /** Deepgram's three tries span about 8 s of one outage (11-09: 7.4-8.6 s, all failed). */
        const val QUIET_MS = 10_000L

        /** Less left than this is not worth an upload: the answer lands as the row ends. */
        const val LEAST_LEFT_MS = 40_000L

        /** What a network event does for the row waiting since [failedAtMs]. */
        internal fun decide(
            pendingId: String,
            soundingId: String?,
            pendingSince: Long,
            sinceNow: Long,
            failedAtMs: Long,
            nowMs: Long,
        ): Step = when {
            pendingId != soundingId || pendingSince != sinceNow -> Step.DROP
            nowMs - failedAtMs < QUIET_MS -> Step.WAIT
            else -> Step.ASK
        }

        /**
         * Whether a row of [durationMs] that became current at [rowSinceMs] has at least
         * [LEAST_LEFT_MS] left. Time since it became current is never less than its
         * position (first audio comes later, pauses add), so this errs towards no.
         * An unknown length asks nothing.
         */
        internal fun enoughLeft(durationMs: Long, rowSinceMs: Long, nowMs: Long): Boolean =
            durationMs > 0L && rowSinceMs > 0L && durationMs - (nowMs - rowSinceMs) >= LEAST_LEFT_MS

        private val _due = MutableSharedFlow<String>(extraBufferCapacity = 4)

        /** The rows to ask once more, read by the lyrics screen for the row it shows. */
        val due: SharedFlow<String> = _due.asSharedFlow()

        val live = HearingReask(
            sounding = { DiagnosticsState.soundingTrackId },
            rowSince = { DiagnosticsState.currentSinceMs },
            ask = { _due.tryEmit(it) },
        )
    }
}
