package com.music.bitchord.playback

import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.widget.MediaWidgetSnapshot

/**
 * The rating the media notification and Android Auto show, and what their next
 * tap means.
 *
 * `LikeState.overrides` only holds ratings this process has already seen or
 * changed. A service started from a widget, a car, or after process death has
 * none until something seeds them, so the star came up empty on a track the
 * listener had liked and the first tap sent another LIKE instead of an unlike.
 * The persisted widget snapshot is the last rating this app published for that
 * same track and is the only surviving statement about it.
 */
internal object SessionFavoriteState {

    fun status(
        videoId: String?,
        override: LikeStatus?,
        persisted: MediaWidgetSnapshot?,
    ): LikeStatus {
        if (videoId == null) return LikeStatus.INDIFFERENT
        if (override != null) return override
        if (persisted != null && persisted.mediaId == videoId && persisted.isLiked) {
            return LikeStatus.LIKE
        }
        return LikeStatus.INDIFFERENT
    }

    fun isLiked(
        videoId: String?,
        override: LikeStatus?,
        persisted: MediaWidgetSnapshot?,
    ): Boolean = status(videoId, override, persisted) == LikeStatus.LIKE

    /**
     * The persisted snapshot a surface may trust right now.
     *
     * It says nothing until the off-main read has finished ([loaded]), and it
     * was written for the identity that was signed in then, so [invalidated]
     * drops it after a sign-out, account switch or other listener reset. A
     * session override already answers for this track and makes it unnecessary.
     */
    fun persistedOrNull(
        loaded: Boolean,
        invalidated: Boolean,
        override: LikeStatus?,
        persisted: MediaWidgetSnapshot?,
    ): MediaWidgetSnapshot? =
        if (!loaded || invalidated || override != null) null else persisted

    /**
     * Whether the star may be offered and tapped. A device file's rating lives
     * in [OfflinePlaylists][com.music.bitchord.data.OfflinePlaylists] and needs
     * no read. An online track with no session override needs [persistedOrNull]
     * to know which way the tap goes, so it has to wait for [loaded]; showing
     * the star first would let a tap send LIKE over a stored unlike.
     */
    fun favoriteActionReady(
        loaded: Boolean,
        onDevice: Boolean,
        override: LikeStatus?,
    ): Boolean = onDevice || override != null || loaded

    /**
     * The star a queue item draws. A device file is liked into the device's own
     * Liked playlist, so [deviceLiked] decides it; an online track goes through
     * [isLiked], which keeps the persisted snapshot after a process restart.
     * [persisted] is only consulted for an online track this process has no
     * rating for, so the caller need not read the snapshot on the hot path.
     */
    fun queueItemLiked(
        videoId: String?,
        onDevice: Boolean,
        deviceLiked: Boolean,
        override: LikeStatus?,
        loaded: Boolean,
        invalidated: Boolean,
        persisted: () -> MediaWidgetSnapshot?,
    ): Boolean {
        if (videoId == null) return false
        if (onDevice) return deviceLiked
        val snapshot = if (override == null) {
            persistedOrNull(
                loaded = loaded,
                invalidated = invalidated,
                override = null,
                persisted = persisted(),
            )
        } else {
            null
        }
        return isLiked(videoId, override, snapshot)
    }

    /** The rating a tap on the star is asking to write. */
    fun toggled(
        videoId: String?,
        override: LikeStatus?,
        persisted: MediaWidgetSnapshot?,
    ): LikeStatus =
        if (status(videoId, override, persisted) == LikeStatus.LIKE) {
            LikeStatus.INDIFFERENT
        } else {
            LikeStatus.LIKE
        }
}
