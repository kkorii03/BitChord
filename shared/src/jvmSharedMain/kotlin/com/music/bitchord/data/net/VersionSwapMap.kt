package com.music.bitchord.data.net

import com.music.bitchord.data.model.VersionTimeMap

/**
 * YouTube's own section-by-section map between an officially paired music
 * video and its song ([VersionTimeMap]), checked before a version swap trusts
 * it with the playhead.
 *
 * With a map nothing is fetched or measured: the swap lands on the matching
 * moment straight away, also past a skit or a cut verse, where one measured
 * offset for the whole track would be wrong. A broken map must not throw the
 * listener far off, so it is only used when every stretch is in order and
 * fits inside both recordings; otherwise the swap measures as before.
 */
object VersionSwapMap {

    /** A catalogue runtime is whole seconds while a decoder's is exact, so lengths get this much room. */
    const val LENGTH_SLACK_MS = 2_000L

    /**
     * [map] when it can be trusted for the pair, else null. [sourceDurationMs]
     * is the playing cut's length and [targetDurationMs] the other's; null or
     * 0 when unknown, which skips that one check.
     */
    fun usable(map: VersionTimeMap?, sourceDurationMs: Long?, targetDurationMs: Long?): VersionTimeMap? {
        // No stretch at all maps nothing (and songEndRefusal reads the last one).
        if (map == null || map.segments.isEmpty()) return null
        var fromEnd = 0L
        var toEnd = 0L
        for (segment in map.segments) {
            if (segment.fromStartMs < 0L || segment.toStartMs < 0L || segment.durationMs <= 0L) return null
            // Overlapping or out of order on either side: the swap would play a
            // stretch twice or jump backwards.
            if (segment.fromStartMs < fromEnd - LENGTH_SLACK_MS) return null
            if (segment.toStartMs < toEnd - LENGTH_SLACK_MS) return null
            fromEnd = segment.fromStartMs + segment.durationMs
            toEnd = segment.toStartMs + segment.durationMs
        }
        val source = sourceDurationMs?.takeIf { it > 0L }
        if (source != null && fromEnd > source + LENGTH_SLACK_MS) return null
        val target = targetDurationMs?.takeIf { it > 0L }
        if (target != null && toEnd > target + LENGTH_SLACK_MS) return null
        return map
    }

    /** Where [positionMs] of the playing cut lands in the other one, kept inside [targetDurationMs]. */
    fun positionMs(map: VersionTimeMap, positionMs: Long, targetDurationMs: Long?): Long {
        val mapped = map.map(positionMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        val target = targetDurationMs?.takeIf { it > 0L } ?: return mapped
        return mapped.coerceAtMost((target - 1L).coerceAtLeast(0L))
    }
}
