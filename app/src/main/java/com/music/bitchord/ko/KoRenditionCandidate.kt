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
    refused: Boolean = false,
): CachedRendition = CachedRendition(
    key = key,
    complete = complete,
    lossless = markedLossless || container in KO_LOSSLESS_CONTAINERS,
    upgradeOrder = upgradeOrder,
    keptOrigin = keptOrigin,
    refused = refused,
)

/**
 * [KO] Review 11-17 (RG1-1)
 *
 * The entry a plain open of a video goes to, given the one the cache would
 * choose now ([chosen], null for "fall through to the plain or sibling key")
 * and the one the player's last plain open of it went to ([playing]).
 *
 * A refusal made while a copy plays (the words audit hears the copy that is
 * sounding) takes that copy out of the choice at once, but a seek reopens the
 * same plain URI and the extractor carries on at the old copy's byte offset:
 * a FLAC offset read from the Opus entry is noise or an error. So a copy the
 * player went to stays the answer while it is whole and refused; the refusal
 * acts from the next play, whose first open has no [playing]
 * ([KoPlaybackReadTracker.reopenPinFor]).
 */
internal fun koKeepRefusedPlayingCopy(
    chosen: String?,
    playing: String?,
    isComplete: (String) -> Boolean,
    isRefused: (String) -> Boolean,
): String? {
    if (playing == null || playing == chosen) return chosen
    return if (isComplete(playing) && isRefused(playing)) playing else chosen
}

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
    refused: (String) -> Boolean = { false },
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
        // Asked only of a copy that could be chosen at all.
        refused = complete && refused(key),
    )
}
