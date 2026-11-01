package com.music.bitchord.ko

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchHistoryAction
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.historyAction
import com.music.bitchord.data.model.isExactArtistMatch
import com.music.bitchord.data.sources.TrackMatcher

/**
 * The album search result that actually answers a name-and-artist lookup.
 *
 * A search for an album title routinely returns covers, tributes and other
 * acts' releases with the same title first. Opening the first row played the
 * wrong album; only an exact title plus a matching credited artist is the
 * release the listener asked for. With no artist stated, an unambiguous title
 * is still safe to open, but several candidates are not.
 */
internal fun chooseAlbumSearchHit(
    results: List<SearchResult>,
    title: String,
    artist: String?,
): BrowseItem? {
    val candidates = results.filterIsInstance<SearchResult.Browse>()
        .map { it.item }
        .filter { it.type == BrowseType.ALBUM && isExactArtistMatch(it.title, title) }
    if (candidates.isEmpty()) return null
    if (artist.isNullOrBlank()) return candidates.singleOrNull()
    return candidates.firstOrNull { albumSubtitleCredits(it.subtitle, artist) }
}

/** Whether an album row's subtitle ("Album • IU • 2019") names [artist]. */
private fun albumSubtitleCredits(subtitle: String, artist: String): Boolean =
    subtitle.split('•', '·', '|')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .any { TrackMatcher.sharesArtist(artist, it) || isExactArtistMatch(it, artist) }

/** Dispatches a tapped search-history row to play, open or re-search. */
internal fun koHandleHistoryAction(
    entity: SearchHistoryEntity,
    onPlay: (Song) -> Unit,
    onOpen: (SearchHistoryAction.Open) -> Unit,
    onSearch: (String) -> Unit,
) {
    when (val action = entity.historyAction()) {
        is SearchHistoryAction.Play -> onPlay(action.song)
        is SearchHistoryAction.Open -> onOpen(action)
        is SearchHistoryAction.Search -> onSearch(action.term)
    }
}
