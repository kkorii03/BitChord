package com.music.bitchord.ko

import com.music.bitchord.playback.CachedRendition
import com.music.bitchord.playback.QualityUpgrade

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

/**
 * [KO] hq-lossless
 *
 * Builds the cache choices for [videoId] from the keys the cache holds.
 *
 * The container is probed only for a complete order-0 copy. An incomplete one
 * cannot be chosen, and opening its file would put blocking disk I/O on the
 * playback key path for nothing.
 */
internal fun losslessRenditionCandidates(
    videoId: String,
    keys: List<String>,
    isComplete: (String) -> Boolean,
    markedLossless: (String) -> Boolean,
    keptOrigin: (String) -> Boolean,
    containerOf: (String) -> Pair<String, String>?,
): List<CachedRendition> = keys.mapNotNull { key ->
    if (key.endsWith("#watch")) return@mapNotNull null
    val suffix = key.removePrefix("$videoId#")
    if (suffix == key) return@mapNotNull null
    val upgradeOrder = QualityUpgrade.upgradeGeneration(suffix)
    val complete = isComplete(key)
    losslessCacheCandidate(
        key = key,
        complete = complete,
        markedLossless = markedLossless(key),
        // The marker is written only by a completed `#hifi` fetch. A
        // FLAC/WAV under `#alt` has none, so read the copy's own container or
        // it stays invisible after a restart.
        container = if (upgradeOrder == 0 && complete) containerOf(key)?.first else null,
        // An order-0 lossless `#alt` may be a different cut or an addon that
        // is no longer configured. Only a kept origin may replace the
        // configured copy, as completeSibling requires.
        keptOrigin = upgradeOrder > 0 || keptOrigin(key),
        upgradeOrder = upgradeOrder,
    )
}
