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

    /**
     * c3-service-paths-2: whether the playing URI is a track picked from an
     * addon/module/JioSaavn/Telegram source (`bitchord://source?...`). Its
     * media id is the source's track key, so re-resolving it as a YouTube id
     * fails twice (direct, then the proxy) and only delays the re-prepare.
     * Same test as the error path and the substitute-length check.
     */
    fun pickedFromSource(uriAuthority: String?): Boolean = uriAuthority == "source"

    /** Whether [candidate] may replace [previous] as the pinned stream. */
    fun mayPin(previous: String?, candidate: String, substituted: Boolean, upgraded: Boolean): Boolean =
        asks(substituted, upgraded) &&
            (previous == null || (candidate != previous && StreamContainer.sameRendition(previous, candidate)))
}
