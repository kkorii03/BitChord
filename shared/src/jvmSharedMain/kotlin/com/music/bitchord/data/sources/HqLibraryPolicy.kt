package com.music.bitchord.data.sources

/**
 * One song's standing in the automatic high-quality library pass.
 *
 * [currentTier] is the rung of the file already on the device, or null when
 * the song is not downloaded. [verifiedTier] is the best rung a sound
 * comparison has proved for it; the source and track ids are what that proof
 * was against, so a replacement can fetch the very same recording rather than
 * searching again and hoping for the same answer.
 *
 * Deliberately plain data: the selection rules below are the part worth
 * testing, and they should not need a device, a download queue or a player to
 * be exercised.
 */
data class HqLibraryEntry(
    val videoId: String,
    /** The rung of the file on disk; null means "not downloaded". */
    val currentTier: HqTier? = null,
    val verifiedTier: HqTier? = null,
    val sourceConfigId: String? = null,
    val trackId: String? = null,
    val plays: Int = 0,
    val liked: Boolean = false,
    /** The song's own title and credit, for the arrangement check. */
    val songTitle: String = "",
    val songArtist: String = "",
    /** The source row's title and uploader, when the verification stored them. */
    val sourceTitle: String? = null,
    val sourceArtist: String? = null,
)

enum class HqLibraryAction {
    /** Replace a downloaded file with a verified higher rung. */
    REPLACE,

    /** Save a verified high-quality copy of a favourite song that is not downloaded. */
    PREFETCH,
}

/** A song the automatic pass should act on, with the proof that earns it. */
data class HqLibraryTarget(
    val videoId: String,
    val action: HqLibraryAction,
    val verifiedTier: HqTier,
    val sourceConfigId: String,
    val trackId: String,
    /** The source row's names, so the fetched file can be checked again later. */
    val sourceTitle: String? = null,
    val sourceArtist: String? = null,
)

data class HqLibraryPlan(
    val replace: List<HqLibraryTarget> = emptyList(),
    val prefetch: List<HqLibraryTarget> = emptyList(),
) {
    val total: Int get() = replace.size + prefetch.size
    val isEmpty: Boolean get() = total == 0
}

/**
 * Which songs the automatic high-quality pass may work on, and in what order.
 *
 * Two separate jobs share this one answer:
 *
 *  - **Replace** every download for which a verified copy sits on a higher
 *    rung — even when the file is already high quality, because a newly proved
 *    hi-res copy still beats yesterday's lossless one.
 *  - **Prefetch** a verified high-quality copy of songs that are not downloaded
 *    yet, most-liked and most-played first, up to a caller's cap.
 *
 * Only a verified pair with both a source and a track id is actionable: a rung
 * with no way back to the stream would ask for a search that could land on a
 * different recording. Entries without one are ignored rather than guessed at.
 */
object HqLibraryPolicy {

    /** A verified rung worth calling "high quality" for prefetching. */
    private val PREFETCH_FLOOR = HqTier.HIGH_LOSSY

    fun isBetter(current: HqTier, verified: HqTier): Boolean = verified.rank < current.rank

    /**
     * The rung a downloaded file is actually on.
     *
     * The display badge only names the container and codec: every FLAC file
     * reads `FLAC`, whether it is 16/44.1 or 24/96, so the badge cannot rank a
     * hi-res file above its own stored provenance. When the file came from a
     * verified pair, [storedHqTier] is the only record of the depth and rate
     * that were actually fetched, and it wins.
     */
    fun currentTier(badgeTier: HqTier, storedHqTier: HqTier?): HqTier =
        storedHqTier ?: badgeTier

    fun isWorthPrefetching(verified: HqTier): Boolean = verified.rank <= PREFETCH_FLOOR.rank

    /**
     * Build the pass's work list.
     *
     * [prefetchLimit] caps only the prefetch half. Replacement is a repair of
     * files the listener already chose to keep, so it is not rationed; the cap
     * exists so pre-saving a large library cannot run without end.
     */
    fun select(
        entries: List<HqLibraryEntry>,
        prefetchLimit: Int,
        replaceEnabled: Boolean = true,
        prefetchEnabled: Boolean = true,
    ): HqLibraryPlan {
        val actionable = entries
            .asSequence()
            .filter { it.videoId.isNotBlank() }
            .filter { it.verifiedTier != null }
            .filter { !it.sourceConfigId.isNullOrBlank() && !it.trackId.isNullOrBlank() }
            // A pair proved before the arrangement check existed, or a
            // catalogue that renamed the row since, must not be fetched again.
            // The same vocabulary the playback check uses keeps a piano cover
            // out of the library pass.
            .filter {
                !DownloadHealthPolicy.isArrangement(
                    sourceTitle = it.sourceTitle,
                    sourceArtist = it.sourceArtist,
                    songTitle = it.songTitle,
                    songArtist = it.songArtist,
                )
            }
            // One song may have been proved against more than one source row.
            // The best rung wins; a second, lower verdict must not hide it just
            // because it was written first.
            .groupBy { it.videoId }
            .mapNotNull { (_, candidates) ->
                candidates.minWithOrNull(
                    compareBy<HqLibraryEntry> { it.verifiedTier?.rank ?: Int.MAX_VALUE }
                        .thenByDescending { it.liked }
                        .thenByDescending { it.plays }
                        .thenBy { it.sourceConfigId.orEmpty() }
                        .thenBy { it.trackId.orEmpty() },
                )
            }
            .toList()

        val replace = if (replaceEnabled) {
            actionable
                .asSequence()
                .filter { entry ->
                    val current = entry.currentTier ?: return@filter false
                    val verified = entry.verifiedTier ?: return@filter false
                    isBetter(current, verified)
                }
                .sortedWith(favouriteOrder)
                .map { it.target(HqLibraryAction.REPLACE) }
                .toList()
        } else {
            emptyList()
        }

        val prefetch = if (prefetchEnabled && prefetchLimit > 0) {
            actionable
                .asSequence()
                .filter { it.currentTier == null }
                .filter { entry ->
                    val verified = entry.verifiedTier ?: return@filter false
                    isWorthPrefetching(verified)
                }
                .sortedWith(favouriteOrder)
                .take(prefetchLimit)
                .map { it.target(HqLibraryAction.PREFETCH) }
                .toList()
        } else {
            emptyList()
        }

        return HqLibraryPlan(replace = replace, prefetch = prefetch)
    }

    /**
     * Liked songs first, then by play count, then by id so the order is stable
     * across runs rather than dependent on map iteration.
     */
    private val favouriteOrder =
        compareByDescending<HqLibraryEntry> { it.liked }
            .thenByDescending { it.plays }
            .thenBy { it.videoId }

    private fun HqLibraryEntry.target(action: HqLibraryAction): HqLibraryTarget =
        HqLibraryTarget(
            videoId = videoId,
            action = action,
            verifiedTier = requireNotNull(verifiedTier),
            sourceConfigId = requireNotNull(sourceConfigId),
            trackId = requireNotNull(trackId),
            sourceTitle = sourceTitle,
            sourceArtist = sourceArtist,
        )
}
