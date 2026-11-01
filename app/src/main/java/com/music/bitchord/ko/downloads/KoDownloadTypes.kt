package com.music.bitchord.ko.downloads

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
