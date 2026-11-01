package com.music.bitchord.ko.ui

import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.playback.contextEntryKey

/**
 * One continuation page folded into an open detail page, or null when it adds
 * nothing new (the feed looped back) or the page is still loading.
 *
 * An empty listing is not a dead end: Liked Music can answer its first page
 * with no songs and no hidden videos, putting the page in the error/empty
 * state, while the only hidden videos arrive on a continuation. Such a page is
 * promoted to Success as soon as the continuation contributes rows, so those
 * rows are not fetched and then thrown away.
 */
internal fun DetailPage.mergeDetailContinuation(
    newSongs: List<Song>,
    newSuggested: List<Song>,
    newHiddenSongs: List<Song>,
    nextContinuation: String?,
    artworkFallback: String?,
): DetailPage? {
    val existing = when (val state = songs) {
        is UiState.Success -> state.data
        is UiState.Error -> emptyList()
        UiState.Loading -> return null
    }
    val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
    val added = newSongs
        .filter { known.add(it.contextEntryKey()) }
        .withArtworkFallback(artworkFallback)
    val knownVideoIds = (existing + added).mapTo(HashSet()) { it.videoId }
    // Suggestions can arrive on a later page than the real tracks, once the
    // playlist's own continuation runs dry — see parsePlaylistShelf — so they
    // are tracked separately rather than folded into [known].
    val knownSuggested = suggestedSongs.mapTo(HashSet()) { it.videoId }
    val addedSuggested = newSuggested
        .filter { it.videoId !in knownVideoIds && knownSuggested.add(it.videoId) }
        .withArtworkFallback(artworkFallback)
    // Liked Music's video rows can arrive on any page; they are counted and
    // revealed together with the first page's.
    val knownHidden = hiddenSongs.mapTo(HashSet()) { it.videoId }
    val addedHidden = newHiddenSongs
        .filter { it.videoId !in knownVideoIds && knownHidden.add(it.videoId) }
        .withArtworkFallback(artworkFallback)
    if (added.isEmpty() && addedSuggested.isEmpty() && addedHidden.isEmpty()) return null
    return copy(
        songs = UiState.Success(existing + added),
        suggestedSongs = suggestedSongs + addedSuggested,
        hiddenSongs = hiddenSongs + addedHidden,
        continuation = nextContinuation,
    )
}

private fun List<Song>.withArtworkFallback(fallback: String?): List<Song> {
    if (fallback == null) return this
    return map { if (it.thumbnailUrl == null) it.copy(thumbnailUrl = fallback) else it }
}
