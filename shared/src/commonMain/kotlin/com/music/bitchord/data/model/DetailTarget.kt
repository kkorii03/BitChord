package com.music.bitchord.data.model

/**
 * The page a card on a detail screen opens when it is tapped.
 *
 * Most cards point at a release, but an artist shelf ("Fans might also like")
 * carries artist cards too, and YouTube's library feed sometimes gives one an
 * id that is not the usual `UC…` prefix. The browse id alone cannot tell those
 * apart, so the card's own [ShelfItem.isArtist] flag decides; a release stays
 * the fallback.
 */
fun ShelfItem.detailBrowseType(fallback: BrowseType = BrowseType.ALBUM): BrowseType = when {
    isArtist -> BrowseType.ARTIST
    browseId?.startsWith("UC") == true -> BrowseType.ARTIST
    else -> fallback
}

/**
 * Whether this card stands for one playable track rather than a page.
 *
 * A track card carries a video id and no browse target; tapping it has to
 * start the track, not search for a page named after it.
 */
val ShelfItem.isTrackCard: Boolean
    get() = browseId.isNullOrBlank() && !videoId.isNullOrBlank()

/**
 * What a release page's track area shows once its first response lands.
 *
 * A page with no tracks of its own is an empty state — except when it has
 * hidden video rows to reveal (Liked Music's videos), which are content the
 * page must keep rather than replace with "No tracks here".
 */
fun detailListingState(
    songs: List<Song>,
    hiddenSongs: List<Song>,
    emptyMessage: String,
): UiState<List<Song>> =
    if (songs.isEmpty() && hiddenSongs.isEmpty()) UiState.Error(emptyMessage)
    else UiState.Success(songs)
