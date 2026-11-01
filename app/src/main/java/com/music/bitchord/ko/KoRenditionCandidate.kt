package com.music.bitchord.ko

import com.music.bitchord.playback.CachedRendition

/** Containers that are lossless whatever their metadata says. */
internal val KO_LOSSLESS_CONTAINERS = setOf("flac", "wav")

/**
 * [KO] hq-lossless
 *
 * Builds one cache choice from what the entry itself holds.
 *
 * The `bc_hq_lossless` marker is written only when the completed `#hifi`
 * prefetch finishes, so an upgrade is already covered. A FLAC/WAV that landed
 * under `#alt` has no marker at all and must be recognised from its own first
 * bytes, or a restart treats the whole copy as an ordinary lossy one.
 *
 * An order-0 lossless rendition may be a different cut of the song, or an
 * addon's file after that addon was turned off. [keptOrigin] keeps it to the
 * same origins [completeSibling] trusts before letting it replace the copy the
 * current settings name.
 */
internal fun losslessCacheCandidate(
    key: String,
    complete: Boolean,
    markedLossless: Boolean,
    container: String?,
    keptOrigin: Boolean,
    upgradeOrder: Int,
): CachedRendition = CachedRendition(
    key = key,
    complete = complete,
    lossless = markedLossless || container in KO_LOSSLESS_CONTAINERS,
    upgradeOrder = upgradeOrder,
    keptOrigin = keptOrigin,
)
