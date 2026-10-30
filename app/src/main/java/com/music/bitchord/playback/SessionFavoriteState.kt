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
