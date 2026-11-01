package com.music.bitchord.ko.ui

import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.playback.contextEntryKey
import com.music.bitchord.ui.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A row already on screen for [videoId], newest page first, so a like can download it without a lookup. */
internal fun MainViewModel.koKnownSong(videoId: String): Song? {
    _detailStack.value.asReversed().forEach { page ->
        (page.songs as? UiState.Success)?.data?.firstOrNull { it.videoId == videoId }?.let { return it }
    }
    (_library.value as? UiState.Success)?.data?.likedSongs?.firstOrNull { it.videoId == videoId }?.let { return it }
    (_history.value as? UiState.Success)?.data?.firstOrNull { it.videoId == videoId }?.let { return it }
    return null
}

/**
 * The detail page's Retry button: replaces the failed page in place with a
 * fresh load at the same stack depth. Re-calling openDetail directly
 * would push a second copy, so Back would need two taps.
 */
internal fun MainViewModel.koRetryDetail(browseId: String) {
    val stack = _detailStack.value
    val index = stack.indexOfLast { it.browseId == browseId }
    if (index < 0) return
    val page = stack[index]
    _detailStack.value = stack.toMutableList().apply { removeAt(index) }
    openDetail(
        browseId = page.browseId,
        title = page.title,
        subtitle = page.subtitle,
        thumbnailUrl = page.thumbnailUrl,
        type = page.type,
    )
}

/**
 * Fills square covers for the rows currently in view, once playback from
 * this page has actually started. The resolver's shared cache and request
 * gate keep repeat calls cheap and the number of lookups bounded.
 */
internal fun MainViewModel.koEnrichVisibleDetailCovers(browseId: String, songs: List<Song>) {
    val pending = songs.filter { song ->
        YtMusicRepository.needsDetailCover(song) &&
            detailCoverLookups.add("$browseId|${song.contextEntryKey()}")
    }
    if (pending.isEmpty()) return
    viewModelScope.launch {
        try {
            val enriched = YtMusicRepository.enrichDetailCovers(pending, pending.size)
            if (enriched == pending) return@launch
            val byKey = enriched.associateBy { it.contextEntryKey() }
            _detailStack.value = _detailStack.value.map { page ->
                if (page.browseId != browseId) return@map page
                val current = (page.songs as? UiState.Success<List<Song>>)?.data
                    ?: return@map page
                page.copy(
                    songs = UiState.Success(
                        current.map { song -> byKey[song.contextEntryKey()] ?: song },
                    ),
                )
            }
        } finally {
            pending.forEach {
                detailCoverLookups.remove("$browseId|${it.contextEntryKey()}")
            }
        }
    }
}

/**
 * The newest snapshot of [browseId] once its real track listing has landed.
 *
 * A playlist opened with a downloaded copy on screen shows that copy before
 * the network response; playing from it queued local/preview rows that
 * carried no upload type at all. Waits on the stack flow instead of polling
 * — the page updates exactly once when the response (or its error) arrives.
 * On timeout the caller gets whatever is on screen and marks those rows as
 * type-unknown, so the song-version lookup still asks about them.
 */
internal suspend fun MainViewModel.koAwaitDetailListing(
    browseId: String,
    timeoutMs: Long = 3_000L,
): DetailPage? {
    fun latest(): DetailPage? = _detailStack.value.lastOrNull { it.browseId == browseId }
    val current = latest() ?: return null
    if (!current.songsArePreview && current.songs !is UiState.Loading) return current
    withTimeoutOrNull(timeoutMs) {
        _detailStack.first { stack ->
            stack.lastOrNull { it.browseId == browseId }?.let {
                !it.songsArePreview && it.songs !is UiState.Loading
            } == true
        }
    }
    return latest() ?: current
}
