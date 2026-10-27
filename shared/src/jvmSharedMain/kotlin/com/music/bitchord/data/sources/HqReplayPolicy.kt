package com.music.bitchord.data.sources

/**
 * What a fresh play of a cached track should open.
 *
 * A track whose bytes are already on disk is normally served from the cache
 * without resolving anything — that is the whole point of the cache. But the
 * cached copy can be the lossy one YouTube served before a verified original
 * was found, while [SourceResolver] already remembers that original for the
 * next play. Serving the cache again would keep replaying the worse copy and
 * never let the remembered answer win.
 *
 * The verdict is deliberately separate from the player and the cache: it is
 * only a truth table over the three facts the caller can observe.
 */
object HqReplayPolicy {

    enum class Choice {
        /** Serve the bytes already on disk. */
        CACHE,

        /** Skip the cached rendition and let the resolver open the remembered original. */
        RESOLVE,
    }

    /**
     * @param cacheIsLossless whether the copy that would be served from disk is
     *   bit-exact. Unknown is not lossless: it is the conservative answer here,
     *   because the alternative is keeping a possibly lossy copy forever.
     * @param hasRememberedOriginal whether [SourceResolver.rememberedOriginal]
     *   has a verified original for this recording.
     * @param offline when true the disk copy is the only one guaranteed to
     *   play, so it always wins.
     */
    fun choose(
        cacheIsLossless: Boolean,
        hasRememberedOriginal: Boolean,
        offline: Boolean,
    ): Choice = when {
        offline -> Choice.CACHE
        cacheIsLossless -> Choice.CACHE
        hasRememberedOriginal -> Choice.RESOLVE
        else -> Choice.CACHE
    }
}
