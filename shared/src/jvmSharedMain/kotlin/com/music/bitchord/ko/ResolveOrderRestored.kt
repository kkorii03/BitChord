package com.music.bitchord.ko

import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog

/*
 * The catalog id inside a profile id, restored after v1.9 removed
 * InnerTubeXResolver.manifestIdForProfile. ResolutionOrder still learns from
 * it, and v1.9 has no equivalent.
 *
 * `WEB_CREATOR__po` is the `WEB_CREATOR` manifest with a PoToken, and is what
 * the override asks for. Null when [profileId] names no catalog entry (legacy
 * ids), in which case there is nothing to remember an order for.
 */
internal fun manifestIdForProfile(profileId: String?): String? {
    val candidate = PlaybackClientCatalog.manifestIdFromProfileId(profileId)
        ?: profileId?.substringBefore("__")
        ?: return null
    return candidate.takeIf { PlaybackClientCatalog.find(it) != null }
}
