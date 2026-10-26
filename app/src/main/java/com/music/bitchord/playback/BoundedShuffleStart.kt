package com.music.bitchord.playback

import com.music.bitchord.data.model.BrowseType

/**
 * Decides whether a shuffle can start from the page already on screen and let
 * the playback service follow the rest of the collection.
 *
 * A long playlist is several continuation pages. Collecting them before the
 * first note made a nine-page mix wait ten seconds, so the start request stays
 * at the first page and [BoundedContextRequests] hands the continuation to the
 * service. Pages that have no continuation, a preview list, a local list or a
 * Cast session keep the full-queue path; an artist page is a selection rather
 * than a running order and is never walked as a bounded context.
 */
object BoundedShuffleStart {

    fun canContinue(
        browseId: String?,
        type: BrowseType,
        preview: Boolean,
        casting: Boolean,
        continuation: String?,
    ): Boolean {
        if (browseId.isNullOrBlank() || browseId.startsWith("local:")) return false
        if (type == BrowseType.ARTIST) return false
        if (preview || casting) return false
        return !continuation.isNullOrBlank()
    }
}
