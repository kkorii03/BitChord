package com.music.bitchord.playback

import androidx.media3.common.MediaItem

/**
 * The exact row a late song-version handover was started for: its media id
 * and its playback URI.
 *
 * The id alone is not enough. "Revert to original" replaces the playing row
 * with the same id at the same index and only a different URI
 * (`direct_youtube=1`), and a handover that compared ids or indexes would
 * commit over that explicit choice. Every step of the handover asks
 * [matches] against the player's current item and stops when it differs.
 */
internal data class LateHandoverRow(val mediaId: String, val uri: String?) {

    fun matches(currentMediaId: String?, currentUri: String?): Boolean =
        currentMediaId == mediaId && currentUri == uri

    fun matches(item: MediaItem?): Boolean =
        matches(item?.mediaId, item?.localConfiguration?.uri?.toString())

    companion object {
        fun of(item: MediaItem): LateHandoverRow =
            LateHandoverRow(item.mediaId, item.localConfiguration?.uri?.toString())
    }
}
