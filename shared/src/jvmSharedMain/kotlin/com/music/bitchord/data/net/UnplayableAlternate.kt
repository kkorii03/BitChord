package com.music.bitchord.data.net

import com.music.bitchord.data.model.Song

/**
 * Picks the copy to play after a definitive refusal, before any new request.
 *
 * The resolver has already ruled the row's own id out. A song version resolved
 * earlier — including one that landed after the pre-audio lookup timed out —
 * is the cheapest rescue: its answer is in hand, so playback can swap to it
 * without another search. Rows that must keep the upload the listener chose
 * (video radio, covers and downloads) are never turned into a song version;
 * for those the next queue item is the replacement.
 *
 * Kept pure and Android-free so the decision is unit-tested.
 */
object UnplayableAlternate {
    /**
     * Whether [row] may be replaced by a catalogue song version at all.
     *
     * A video-radio row is the upload the listener asked to keep, a cover is
     * the performance they chose, and a downloaded row is the file they own —
     * none of them becomes a different recording to escape a failure.
     */
    fun allowsSongVersion(row: Song, downloaded: Boolean): Boolean =
        !row.videoRadio &&
            row.localUri == null &&
            !downloaded &&
            SongVersionPolicy.wantsSongVersion(row.isVideo, row.typeUnknown) &&
            SongVersionPolicy.mayInstallSongVersion(row)

    /**
     * The already-resolved song version to swap in for [row], or null when
     * there is none to use.
     *
     * [cached] is the repository's answer for this row's id, found either
     * before audio or by the late lookup after `kept (timeout)`. A candidate
     * that is the failed id itself, another video, or already marked
     * unplayable is not a rescue.
     */
    fun cachedSongVersion(
        row: Song,
        cached: Song?,
        downloaded: Boolean,
        unplayable: Boolean,
    ): Song? {
        if (!allowsSongVersion(row, downloaded)) return null
        val candidate = cached ?: return null
        if (candidate.videoId == row.videoId) return null
        if (candidate.isVideo) return null
        if (unplayable) return null
        return candidate
    }

    /**
     * The part of [budgetMs] still available for the replacement search after
     * [elapsedMs] has already passed since the definitive verdict.
     *
     * The 2.5 s replacement budget is for the listener's silence, not for one
     * request: work before the search (reading the cache, a download record)
     * comes out of the same allowance. A negative or already-spent result is
     * zero, which means skip now rather than start a search that can only
     * outlive the budget.
     */
    fun searchBudgetMs(elapsedMs: Long, budgetMs: Long): Long {
        if (budgetMs <= 0L) return 0L
        return (budgetMs - elapsedMs.coerceAtLeast(0L)).coerceIn(0L, budgetMs)
    }
}
