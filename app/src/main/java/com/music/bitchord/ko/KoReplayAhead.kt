package com.music.bitchord.ko

import android.net.Uri
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.automaticHqLookAllowed
import com.music.bitchord.playback.DirectSourceGate
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice

/**
 * [KO] replay-ahead: Direct Source Stream's window for a next track that is
 * still pinned to YouTube from an earlier play.
 *
 * [StreamChoice] keeps a pin for 15 minutes, so a song heard (or skipped) a
 * few minutes ago comes back with its YouTube URL still pinned. The window's
 * read-ahead looked only when nothing was pinned, so for that song no addon
 * lookup ran before it started, and the second look began from zero at its
 * first note (phone 11-18 16:00, WKZO-CWeOVA: pinned 15:50:10, window open
 * 15:59:31 with no lookup, Private Hi-Res FLAC found only at 16:00:01, the
 * listener tapped upgrade at 16:00:10).
 *
 * This starts the same shared lookup the window starts for a fresh track and
 * hands it to the second look, exactly as [DirectSourceGate.startedOnYouTube]
 * does when the addons miss the window. The pin is left alone: the cache entry
 * it fills stays YouTube's, and the proved copy swaps in under the music.
 *
 * Event-driven: called once per window opening from AudioCache.prefetchDirect,
 * which the playing sampler's existing tick drives. No timer, nothing after a
 * seek or a skip (the window does not open then), nothing in low power, and
 * nothing for a track whose second look already has its answer.
 */
internal object KoReplayAhead {

    /** What [QualityUpgrade.couldStillUpgrade] says for [mediaId]'s plain row; a test swaps it (android.net.Uri is a stub there). */
    internal var couldStillUpgrade: (String) -> Boolean = {
        QualityUpgrade.couldStillUpgrade(it, Uri.parse("bitchord://watch?v=$it"))
    }

    /** Whether the window should look for a better copy of a pinned next track. */
    internal fun wanted(
        pinnedToYouTube: Boolean,
        heavyWorkAllowed: Boolean,
        alreadyPending: Boolean,
        couldStillUpgrade: Boolean,
    ): Boolean = pinnedToYouTube && heavyWorkAllowed && !alreadyPending && couldStillUpgrade

    /**
     * Starts the lookup for [id], pinned to YouTube, and hands it to the
     * second look. Returns whether one was started.
     */
    fun start(id: String, target: TrackMatcher.Target): Boolean {
        // The same rows [QualityUpgrade.settledForLess] turns away, before any search starts.
        if (target.title.isBlank() || target.isVideo) return false
        if (!wanted(
                pinnedToYouTube = StreamChoice.isYouTube(id),
                heavyWorkAllowed = automaticHqLookAllowed(manual = false, heavyWorkAllowed = AppSettings.heavyWorkAllowed),
                alreadyPending = QualityUpgrade.isPending(id),
                couldStillUpgrade = couldStillUpgrade(id),
            )
        ) return false
        // Null when every addon is resting: the second look searches at the start as before.
        DirectSourceGate.startLookup(id, target) ?: return false
        TrackLog.d(TAG, "replay-ahead: $id is still pinned to YouTube; looking for its better copy in the window", about = id)
        DirectSourceGate.startedOnYouTube(id, target)
        return true
    }

    private const val TAG = "BitChord"
}
