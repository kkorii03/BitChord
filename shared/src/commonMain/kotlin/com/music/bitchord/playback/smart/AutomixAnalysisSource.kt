package com.music.bitchord.playback.smart

/**
 * Keeps Automix analysis on the canonical YouTube Opus rendition.
 *
 * Playback may deliberately use a source substitute (including JioSaavn), but
 * its cache entry is not interchangeable with the YouTube recording that the
 * analysis fetcher owns. The plain video id is the cache key pinned to that
 * YouTube copy; suffixed keys (`#alt`, `#hifi`, ...) belong to substitutes or
 * upgrades.
 */
 object AutomixAnalysisSource {
    const val OPUS_QUERY_PARAMETER = "automix_opus"

    fun isCanonicalYouTubeRendition(videoId: String?, cacheKey: String): Boolean =
        videoId == null || cacheKey == videoId

    /**
     * Whether [cacheKey] names a substituted rendition of [videoId] rather than
     * YouTube's own copy.
     *
     * [AudioCache] writes the substitute upload under `#alt` (or `#alt-noatmos`
     * when Atmos is off), and a quality upgrade under `#hifi` or `#hifi-N`.
     * `#original` is YouTube's own copy after a hand revert, and `#watch` is
     * the watch app's duplicate of whatever it follows, so neither is a
     * substitute. Shared so every caller recognises the whole family.
     */
    fun isSubstituteRendition(videoId: String?, cacheKey: String): Boolean {
        if (videoId != null && cacheKey == videoId) return false
        return isSubstituteRendition(cacheKey)
    }

    /** [isSubstituteRendition] for a key whose track id is not at hand. */
    fun isSubstituteRendition(cacheKey: String): Boolean {
        val tag = cacheKey.substringAfter('#', "").substringBefore('#')
        return tag == "alt" || tag.startsWith("alt-") ||
            tag == "hifi" || tag.startsWith("hifi-")
    }

    /**
     * A private playback URI for analysis-only reads.
     *
     * A normal `watch?v=` URI is allowed to reuse the source race already
     * pinned for audible playback. That is exactly the wrong thing for
     * Automix: its independently cached copy must always be YouTube Opus,
     * even while playback is JioSaavn or a lossless upgrade.
     */
    fun opusUri(videoId: String): String = "bitchord://watch?v=$videoId&$OPUS_QUERY_PARAMETER=1"

    fun requestsYouTubeOpus(marker: String?): Boolean = marker == "1"
}
