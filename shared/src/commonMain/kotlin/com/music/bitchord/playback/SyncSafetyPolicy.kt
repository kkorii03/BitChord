package com.music.bitchord.playback

import com.music.bitchord.playback.NextPrepPolicy.Step

/**
 * Which of the post-ko-101 lyrics-sync paths are allowed to run.
 *
 * Every switch defaults off. The paths they guard were added after ko-101,
 * whose sync the listener asked to keep, and each one can move or replace a
 * song's lyrics: the next-song bundle's lyrics/sync steps and first-frame
 * cache, the downloaded-file re-check and its override file, and the saved
 * HQ offset/rate applied on top of a song's timing. Turning a switch back on
 * runs the existing code; nothing here deletes it.
 */
object SyncSafetyPolicy {

    enum class Path { NEXT_PREP_LYRICS, DOWNLOADED_RECHECK, HQ_REAPPLY }

    data class Switches(
        val nextPrepLyrics: Boolean = false,
        val downloadedRecheck: Boolean = false,
        val hqReapply: Boolean = false,
    )

    fun isEnabled(path: Path, switches: Switches): Boolean = when (path) {
        Path.NEXT_PREP_LYRICS -> switches.nextPrepLyrics
        Path.DOWNLOADED_RECHECK -> switches.downloadedRecheck
        Path.HQ_REAPPLY -> switches.hqReapply
    }

    /**
     * The next-song steps to run. With the lyrics switch off, [Step.LYRICS]
     * and [Step.SYNC] are dropped; version, HQ and mix are untouched.
     */
    fun nextPrepSteps(wanted: Set<Step>, switches: Switches): Set<Step> =
        if (switches.nextPrepLyrics) wanted else wanted - LYRICS_AND_SYNC

    /** Whether the bundle's lyrics may be published on the next track's first frame. */
    fun firstFrameCacheAllowed(switches: Switches): Boolean = switches.nextPrepLyrics

    /** Whether a stored re-check override may be read at all. */
    fun overrideReadAllowed(switches: Switches): Boolean = switches.downloadedRecheck

    /** Whether a re-check may write its result to the override store. */
    fun overrideWriteAllowed(switches: Switches): Boolean = switches.downloadedRecheck

    /** Whether [com.music.bitchord.data.lyrics.DownloadedSyncCheck] may run. */
    fun recheckAllowed(switches: Switches): Boolean = switches.downloadedRecheck

    /**
     * Why next-prep left the next row's sync unapplied, as one log token.
     *
     * The bundle used to publish only `sync=skipped`, which could not tell a
     * disabled safety switch from a video row, a low-power deferral, a missing
     * Deepgram key or lyrics that simply had no timed line. The conditions are
     * checked in the same order the steps use, and null means sync was applied
     * (or is not the reason this function exists to explain).
     */
    fun nextPrepSyncSkipReason(
        switches: Switches,
        syncedLyrics: Boolean,
        isVideo: Boolean,
        heavyWorkAllowed: Boolean,
        aiTimingAhead: Boolean,
        deepgramUsable: Boolean,
        hasLyrics: Boolean,
        hasTimedLines: Boolean,
    ): String? {
        if (!switches.nextPrepLyrics) return "next-prep-lyrics-off"
        if (!syncedLyrics) return "lyrics-off"
        if (isVideo) return "video"
        if (!heavyWorkAllowed) return "low-power"
        if (!aiTimingAhead) return "timing-ahead-off"
        if (!deepgramUsable) return "deepgram-unavailable"
        if (!hasLyrics) return "no-lyrics"
        if (!hasTimedLines) return "no-timed-lines"
        return null
    }

    /** Whether a saved HQ offset/rate may be applied to lyrics. */
    fun hqAdjustmentAllowed(switches: Switches): Boolean = switches.hqReapply

    private val LYRICS_AND_SYNC = setOf(Step.LYRICS, Step.SYNC)
}
