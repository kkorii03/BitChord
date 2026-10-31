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
     * The identity a row's prepared work belongs to: the queue row and the
     * recording that will actually play for it. A row whose song version is
     * resolved from the video to the record is a different recording, and its
     * attempts, lyrics and sync must not be inherited from the video.
     */
    fun stateKey(rowId: String, videoId: String): String = "$rowId|$videoId"

    /**
     * Whether the song-sync step may run for this row. A video is timed by the
     * measurement/AI path instead, and a substitute stream has a different
     * clock from the recording the stored answer was made against.
     */
    fun syncMayRun(isVideo: Boolean, substituted: Boolean): Boolean = !isVideo && !substituted

    /**
     * Whether a song's own sync check may start.
     *
     * A missing timing entry is not a reason to skip it. The next row is
     * usually a track that has never played, so no entry has been written for
     * it yet; the check itself creates that entry when it records its answer
     * (or a 429 hold). Requiring one first made every first-time next row end
     * at `sync=skipped(no-answer)` without ever asking an engine.
     *
     * [hasEntry] is kept in the signature so the old rule — and the regression
     * it caused — stays explicit at every call site and in the tests.
     */
    fun songCheckMayStart(
        hasEntry: Boolean,
        retryHeld: Boolean,
        engineAllowed: Boolean,
    ): Boolean = !retryHeld && engineAllowed

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
     * A paid AI call (Gemini or Deepgram) is only worth making after the song
     * actually playing has held the listener for this long. A skip inside the
     * first half minute is common, and the next row's sync would be bought for
     * a track nobody heard.
     */
    const val AI_MIN_PLAYED_MS = 30_000L

    /**
     * The bundle must be out of the way before the current track's last twenty
     * seconds: that is when the player starts preparing the handoff, and the
     * next row's work must not compete with it.
     */
    const val END_MARGIN_MS = 20_000L

    /** How much time is left for preparation before the current track's handoff. */
    fun remainingMs(durationMs: Long, positionMs: Long): Long =
        durationMs - positionMs - END_MARGIN_MS

    /** Whether a step may still start with [remainingMs] left. */
    fun canStartStep(remainingMs: Long): Boolean = remainingMs > 0L

    /** Whether the paid engines may be asked after [playedMs] of the current track. */
    fun aiGateOpen(playedMs: Long): Boolean = playedMs >= AI_MIN_PLAYED_MS

    /**
     * Whether the next row may be replaced by a lossless audio candidate.
     *
     * A video-radio row is a video upload even when a catalogue parser did not
     * set [com.music.bitchord.data.model.Song.isVideo]: keeping the upload the
     * listener chose is the whole point of that mode. The version step already
     * refuses those rows; the lossless step has to use the same predicate or
     * it can swap the upload's sound for a rejected-candidate FLAC.
     */
    fun hqStepWanted(
        isVideo: Boolean,
        videoRadio: Boolean,
        localFile: Boolean,
        sourceKey: Boolean,
        canSubstitute: Boolean,
    ): Boolean = !isVideo && !videoRadio && !localFile && !sourceKey && canSubstitute


    /**
     * What the next row's sync can say about itself. [hasTiming] is the answer
     * after the existing measurement/AI/refine path has run; everything else
     * describes why that path could not produce one.
     */
    data class SyncFacts(
        /** The developer switch for the bundle's lyrics/sync path is off. */
        val lyricsOff: Boolean = false,
        /** The listener's synced-lyrics setting is off, so the step was never wanted. */
        val syncedLyrics: Boolean = true,
        val hasLyrics: Boolean = false,
        /** The lyrics carry provider times; a song check can only move those. */
        val hasTimedLines: Boolean = true,
        /** The lyrics came from a file whose own clock is not the video's. */
        val fileTimed: Boolean = false,
        /** Whether the lyrics' clock may be measured against the audio at all. */
        val recordTimed: Boolean = true,
        /** The provider's times are already the video's own, so nothing may move them. */
        val timedToVideo: Boolean = false,
        val heavyWorkAllowed: Boolean = true,
        val aiTimingAhead: Boolean = true,
        val aiUsable: Boolean = true,
        val currentPlayedMs: Long = 0L,
        val remainingMs: Long = 1L,
        val hasTiming: Boolean = false,
    )

    /**
     * The token for `sync=skipped(<reason>)`, or null when a timing exists and
     * the line says `sync=applied`. Ordered so the reason names the first thing
     * that actually stopped the path: the switches and settings that meant the
     * step was never wanted, no lyrics at all, a clock that must not be
     * measured (the video's own times, or a file's foreign clock), the
     * end-of-track deadline, then the AI gates.
     */
    fun syncSkipReason(facts: SyncFacts): String? = when {
        facts.hasTiming -> null
        facts.lyricsOff -> "lyrics-off"
        !facts.syncedLyrics -> "synced-off"
        !facts.hasLyrics -> "no-lyrics"
        !facts.hasTimedLines -> "no-timed-lines"
        facts.timedToVideo -> "timed-to-video"
        !facts.recordTimed || facts.fileTimed -> "file-timed"
        !facts.heavyWorkAllowed -> "heavy-off"
        facts.remainingMs <= 0L -> "deadline"
        !facts.aiTimingAhead -> "ai-ahead-off"
        !facts.aiUsable -> "ai-unavailable"
        !aiGateOpen(facts.currentPlayedMs) -> "ai-too-early"
        else -> "no-answer"
    }

    /** The exact `sync=` value for the next-prep line. */
    fun syncStatus(facts: SyncFacts): String =
        if (facts.hasTiming) "applied" else "skipped(${syncSkipReason(facts) ?: "no-answer"})"

    /** A value for a step that has no answer, with the reason that stopped it. */
    fun noneWithReason(reason: String?): String = "none(${reason ?: "none"})"

    /**
     * The identity of the sound a bundle is for: the row, the recording that
     * will play, whether it is a downloaded file, and the lyrics settings the
     * answer was chosen with.
     *
     * A change to any part means an old answer is not the answer for the new
     * sound. In particular a lyrics settings change must not let the lines
     * chosen under the old source set/order be stored or shown as the new
     * choice, so it is part of this identity rather than a separate check.
     */
    fun soundKey(
        rowId: String,
        videoId: String,
        localFile: Boolean,
        lyricsSettings: String,
    ): String = "$rowId|$videoId|$localFile|$lyricsSettings"

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
