package com.music.bitchord.playback

/**
 * Pure rules for the one-track read-ahead bundle.
 *
 * The bundle runs once after the current track makes its first sound. It has
 * five ordered steps — version, lyrics, high-quality candidate, song sync
 * check, mix analysis — and each is worth at most one attempt for the row it
 * was started for. A step whose answer is already stored is skipped, so
 * replaying a song costs no requests at all.
 *
 * Everything here is deliberately free of Android, players and stores: the
 * caller says what it wants and what is already known, and this decides what
 * remains and whether a running bundle is still about the same next row.
 */
object NextPrepPolicy {

    enum class Step { VERSION, LYRICS, HQ, SYNC, MIX }

    /**
     * What the caller wants for the row, what is already stored, and what has
     * already been tried during this session for that row.
     */
    data class Request(
        /** A stable identity for the next queue row (its queue entry id). */
        val rowId: String?,
        /** The queue pair this bundle belongs to; see [shouldCancel]. */
        val queueKey: String?,
        val wanted: Set<Step>,
        val prepared: Set<Step> = emptySet(),
        val attempted: Set<Step> = emptySet(),
    )

    data class Plan(
        val rowId: String?,
        val queueKey: String?,
        val steps: List<Step>,
    ) {
        val isEmpty: Boolean get() = steps.isEmpty()
    }

    /**
     * The steps still worth running, in their fixed order: wanted, not already
     * stored, and not already tried for this row.
     */
    fun plan(request: Request): Plan {
        if (request.rowId.isNullOrBlank()) return Plan(null, request.queueKey, emptyList())
        val steps = Step.entries.filter {
            it in request.wanted && it !in request.prepared && it !in request.attempted
        }
        return Plan(request.rowId, request.queueKey, steps)
    }

    /**
     * Whether a running bundle must stop.
     *
     * It is about one queue row: when that row is replaced, removed, or the
     * queue moves so that a different row is next, its answers are no longer
     * the ones the player will need. A changed current track matters only
     * because it changes the pair; the row identity is the thing that decides.
     */
    fun shouldCancel(
        startedRowId: String?,
        startedQueueKey: String?,
        currentRowId: String?,
        currentQueueKey: String?,
    ): Boolean {
        if (startedRowId == null) return false
        if (currentRowId != startedRowId) return true
        return startedQueueKey != currentQueueKey
    }

    /** What a schedule call should do with the bundle it finds. */
    enum class ScheduleAction {
        /** The same row and recording is already running or finished. */
        IGNORE,

        /** A different row or recording must be prepared. */
        RESTART,

        /** There is no next row to prepare. */
        STOP,
    }

    /**
     * What to do with the bundle already in hand.
     *
     * A running bundle is only still about the same recording when both the
     * row and its [queueKey] match. The old rule compared the row id alone and
     * returned before [shouldCancel], so a version swap of the same next row
     * left the bundle finishing against the old recording and never restarted
     * for the new one.
     */
    fun scheduleAction(
        running: Boolean,
        startedRowId: String?,
        startedQueueKey: String?,
        completedRowId: String?,
        completedQueueKey: String?,
        nextRowId: String?,
        nextQueueKey: String?,
    ): ScheduleAction {
        if (nextRowId.isNullOrBlank()) return ScheduleAction.STOP
        if (nextRowId == startedRowId) {
            if (running && nextQueueKey == startedQueueKey) return ScheduleAction.IGNORE
            if (!running && completedRowId == nextRowId && completedQueueKey == nextQueueKey) {
                return ScheduleAction.IGNORE
            }
        }
        return ScheduleAction.RESTART
    }

    /**
     * The identity of one recording of a queue row, used for the per-row
     * attempts and lyrics memos.
     *
     * A row keeps its id across a version swap, so keying either memo by the
     * row alone let the old recording's lyrics and attempted steps answer for
     * the new one. The video id is part of the key.
     */
    fun recordingKey(rowId: String, videoId: String): String = "$rowId|$videoId"

    /**
     * Whether the sync flight started for a next row should be cancelled when
     * that bundle is abandoned.
     *
     * Becoming the current track is not abandonment: the same flight is what
     * the track's own lyrics pass would use. A row that was removed, skipped
     * over, or replaced by another recording is, and its paid check must not
     * outlive it.
     */
    fun abandonSyncFlight(
        rowId: String,
        songVideoId: String,
        currentRowId: String?,
        currentMediaId: String?,
    ): Boolean = currentRowId != rowId || currentMediaId != songVideoId
}
