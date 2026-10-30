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
     * Whether [nextIndex] is a row other than the one playing at
     * [currentIndex].
     *
     * Media3's next index is not "is there something after this": with
     * REPEAT_MODE_ALL on a single-item queue it wraps to the current row.
     * Treating that as the next row starts a bundle for the song already
     * playing, which then waits the full mix timeout for a transition that
     * cannot happen. A null index means the player has no such row.
     */
    fun hasDistinctNextRow(nextIndex: Int?, currentIndex: Int?): Boolean =
        nextIndex != null && nextIndex != currentIndex

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

    /**
     * Whether the row's current bundle already covers the sound it will play.
     *
     * The row id alone is not enough: a download finishing or a file being
     * replaced by its stream keeps the row but changes the audio. An active
     * job for the old sound must not make the caller skip the new one, and a
     * completed bundle for the old sound must not stand for the new one.
     */
    fun alreadyHandled(
        startedRowId: String?,
        startedQueueKey: String?,
        rowId: String,
        queueKey: String,
        running: Boolean,
        completedQueueKey: String?,
    ): Boolean {
        if (startedRowId != rowId || startedQueueKey != queueKey) return false
        return running || completedQueueKey == queueKey
    }
}
