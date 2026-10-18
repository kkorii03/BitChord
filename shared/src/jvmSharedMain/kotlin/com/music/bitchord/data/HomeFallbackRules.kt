package com.music.bitchord.data

/**
 * Ranking rules for the gated Home that do not need a request.
 *
 * Kept public and apart from [YouTubeFallbackFeed] because the application's
 * listening store applies the rediscovery rule when it answers
 * [LocalPlayHistory.Source.rediscover], while the fallback applies the
 * interleaving rule when it builds Quick picks. Both are pure so the rule can
 * be read and tested without a network or a player.
 */
object HomeFallbackRules {

    /** How old a last play has to be before a song is a rediscovery. */
    const val DEFAULT_REDISCOVER_DAYS = 14

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * The songs worth offering again: those whose lifetime play count is
     * highest, once their last play is older than [olderThanDays] days. The row
     * that comes back is ordered oldest last play first, so the song not heard
     * for longest is offered first.
     *
     * [LocalPlay.plays] is the lifetime count of each candidate; a row with a
     * blank video id is ignored. The same id twice keeps the row with more
     * plays (and its latest play), rather than being offered twice.
     */
    fun rediscover(
        candidates: List<LocalPlay>,
        nowMs: Long,
        limit: Int,
        olderThanDays: Int = DEFAULT_REDISCOVER_DAYS,
    ): List<LocalPlay> {
        if (limit <= 0) return emptyList()
        val cutoffMs = olderThanDays.coerceAtLeast(0) * DAY_MS
        return candidates.asSequence()
            .filter { it.song.videoId.isNotBlank() }
            .filter { it.playedAtMs > 0L && nowMs - it.playedAtMs > cutoffMs }
            .sortedWith(
                compareByDescending<LocalPlay> { it.plays }
                    .thenBy { it.playedAtMs },
            )
            .distinctBy { it.song.videoId }
            .take(limit)
            .sortedBy { it.playedAtMs }
            .toList()
    }

    /**
     * Alternates [first] and [second] until [limit] items have been taken.
     *
     * The first list leads, so a listener whose rediscoveries outnumber their
     * mix still sees one of each early rather than twenty rediscoveries before
     * the first mix row.
     */
    fun <T> interleave(first: List<T>, second: List<T>, limit: Int): List<T> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<T>(limit)
        var index = 0
        while (out.size < limit && (index < first.size || index < second.size)) {
            first.getOrNull(index)?.let(out::add)
            if (out.size >= limit) break
            second.getOrNull(index)?.let(out::add)
            index++
        }
        return out
    }
}
