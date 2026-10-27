package com.music.bitchord.playback

/**
 * One on-disk rendition of a track, as the cache-key factory sees it.
 *
 * [upgradeOrder] is the generation of an upgraded rendition (`hifi` is 1,
 * `hifi-2` is 2), or zero for the base/`#alt` copies. [lossless] is only a
 * tie-break between two complete upgrades: both are already better than the
 * copy they replaced, but the bit-exact one is the one the listener asked for.
 */
internal data class CachedRendition(
    val key: String,
    val complete: Boolean,
    val lossless: Boolean,
    val upgradeOrder: Int,
)

/**
 * Which cache entry a plain play request should open.
 *
 * A completed upgrade lives under its own rendition key (`videoId#hifi`), while
 * the queue entry keeps the unmarked URI. The old rule asked only for the base
 * key and returned it as soon as it held any bytes, so a low-quality Opus copy
 * written on an earlier play kept winning forever even after the lossless
 * rendition had been downloaded in full. A complete upgrade is never written to
 * again, so reading it cannot create the mixed-file seam the key split exists
 * to prevent.
 *
 * An incomplete upgrade is not a candidate: it would stream the rest from a
 * different URL into a half-written entry, and the complete low-quality copy is
 * still better than that.
 */
internal object CacheRenditionChoice {

    fun choose(preferred: String, candidates: List<CachedRendition>): String =
        candidates
            .filter { it.complete && it.upgradeOrder > 0 }
            .maxWithOrNull(
                compareBy<CachedRendition> { it.lossless }
                    .thenBy { it.upgradeOrder },
            )
            ?.key
            ?: preferred
}
