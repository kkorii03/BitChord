package com.music.bitchord.ko.downloads

import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier

/**
 * What an automatic high-quality download's failure means to the pass.
 *
 * [transient] keeps the target in the plan and schedules it again; a permanent
 * failure lets the pass move on. [retryAfterHeader] is the server's own wait,
 * when the response carried one.
 */
internal data class DownloadFailure(
    val transient: Boolean,
    val retryAfterHeader: String? = null,
    val retryAfterMs: Long? = null,
)

/**
 * The verified pair a high-quality replacement must fetch.
 *
 * Both ids are the ones the sound comparison stored: searching again could
 * pick a different recording with the same title. [tier], [offsetMs] and
 * [rate] are written into the new file's metadata so the provenance and the
 * lyric clock survive the replacement.
 */
data class HqDownloadSource(
    val sourceConfigId: String,
    val trackId: String,
    val tier: HqTier,
    val offsetMs: Long = 0L,
    val rate: Double = 1.0,
    /** The source row's own names, for the arrangement check on a later play. */
    val sourceTitle: String? = null,
    val sourceArtist: String? = null,
)

/**
 * What a failed download was actually asked for, kept with its manager row.
 *
 * [com.music.bitchord.download.DownloadSession.Item] is what the Retry button
 * has, and rebuilding the call from the song alone dropped the replacement's
 * quality, pinned pair and force-replace flag. The retry then looked like an
 * ordinary download: it could adopt the old lower-rung file and report success
 * without the upgrade the listener asked for. This carries those terms until
 * the row settles.
 */
data class DownloadRetryPlan(
    val allowMetered: Boolean = false,
    val quality: DownloadQuality? = null,
    val hq: HqDownloadSource? = null,
    val avoidSourceConfigId: String? = null,
    val forceReplace: Boolean = false,
    val youtubeOnly: Boolean = false,
    val minimumTier: HqTier? = null,
    val mvSoundRepair: Boolean = false,
)
