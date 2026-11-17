package com.music.bitchord.ko

import com.music.bitchord.playback.StreamContainer

/**
 * [KO] SW7ps-1: the silence ladder's last rung (re-resolve the current stream).
 *
 * It used to pin whatever YouTube URL the resolver answered, even while a
 * Telegram/module/addon substitute or an accepted mid-song upgrade was the
 * sound. That flipped the lyric clock to YouTube while the substitute kept
 * playing, and past the cached span spliced WebM bytes into the substitute's
 * cache entry. The other mid-play re-resolves (KoPlaybackRecovery,
 * KoChunkedMidPlayRecovery) already keep the rendition; this one now does too.
 */
internal object KoSilenceReresolve {

    /** Whether the rung may ask for a new stream at all; otherwise it only re-prepares. */
    fun asks(substituted: Boolean, upgraded: Boolean): Boolean = !substituted && !upgraded

    /** Whether [candidate] may replace [previous] as the pinned stream. */
    fun mayPin(previous: String?, candidate: String, substituted: Boolean, upgraded: Boolean): Boolean =
        asks(substituted, upgraded) &&
            (previous == null || (candidate != previous && StreamContainer.sameRendition(previous, candidate)))
}
