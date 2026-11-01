package com.music.bitchord.ko

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.contextEntryKey

/**
 * Enriches the covers of the rows currently visible on a release page, but
 * only once playback from that page has actually started.
 */
@Composable
internal fun KoDetailCoversEffect(
    stateKey: Any?,
    currentSongVideoId: String?,
    currentPlaybackSourceId: String?,
    isPlaying: Boolean,
    pageBrowseId: String,
    liveBrowseId: String?,
    pageSongs: List<Song>,
    listState: LazyListState,
    onEnrich: (String, List<Song>) -> Unit,
) {
    var armed by remember(stateKey) { mutableStateOf(false) }
    val playingFromThisPage = currentSongVideoId != null &&
        (currentPlaybackSourceId == pageBrowseId ||
            pageSongs.any { it.videoId == currentSongVideoId })
    LaunchedEffect(
        playingFromThisPage,
        isPlaying,
        pageBrowseId,
        liveBrowseId == pageBrowseId,
    ) {
        if (playingFromThisPage && isPlaying && liveBrowseId == pageBrowseId) {
            armed = true
        }
    }
    LaunchedEffect(
        pageBrowseId,
        armed,
        liveBrowseId == pageBrowseId,
        listState,
        pageSongs,
    ) {
        if (!armed || liveBrowseId != pageBrowseId) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val byId = pageSongs.associateBy { it.videoId }
            listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                val itemKey = item.key as? String ?: return@mapNotNull null
                val videoId = if (itemKey.firstOrNull()?.isDigit() == true) {
                    itemKey.substringAfter('_')
                } else {
                    itemKey
                }
                byId[videoId]
            }.distinctBy { it.contextEntryKey() }
        }.collect { visible ->
            if (visible.isNotEmpty()) {
                onEnrich(pageBrowseId, visible)
            }
        }
    }
}

/** A page opened from its downloaded copy can be tapped before the network listing lands. */
internal suspend fun koPlayDetailSongs(
    page: com.music.bitchord.data.model.DetailPage,
    songs: List<Song>,
    index: Int,
    isCurrentRequest: () -> Boolean,
    takePlaybackContext: (String) -> com.music.bitchord.data.model.DetailPage?,
    awaitDetailListing: suspend (String) -> com.music.bitchord.data.model.DetailPage?,
    withAlbum: (Song) -> Song,
    playFrom: (List<Song>, Int, com.music.bitchord.playback.QueueSource, Boolean, String?) -> kotlinx.coroutines.Job?,
) {
    // Start from what is on screen; only wait when there is nothing to play yet.
    val immediate = takePlaybackContext(page.browseId)
    val hasImmediate =
        (immediate?.songs as? com.music.bitchord.data.model.UiState.Success)?.data?.isNotEmpty() == true
    val settled = if (hasImmediate) {
        immediate
    } else {
        awaitDetailListing(page.browseId)
    }
    if (!isCurrentRequest()) return
    val preview = settled?.songsArePreview == true
    val settledSongs =
        (settled?.songs as? com.music.bitchord.data.model.UiState.Success)?.data
    val pageSongKeys =
        (settledSongs ?: (page.songs as? com.music.bitchord.data.model.UiState.Success)?.data).orEmpty()
            .mapTo(HashSet()) { it.contextEntryKey() }
    val selected = songs.getOrNull(index)
    val useBounded =
        (page.type == com.music.bitchord.data.model.BrowseType.ALBUM ||
            page.type == com.music.bitchord.data.model.BrowseType.PLAYLIST) &&
            selected?.contextEntryKey()?.let(pageSongKeys::contains) == true &&
            !com.music.bitchord.playback.cast.CastController.state.value.casting
    // The page snapshot on screen can be one fill-in behind, or an older visit
    // restored from the saved context. The view model's newest list is what the
    // tap is meant to play.
    val latestPage = takePlaybackContext(page.browseId)
    val latestSongs = latestPage
        ?.let { (it.songs as? com.music.bitchord.data.model.UiState.Success)?.data.orEmpty() }
        ?.map(withAlbum)
        ?.takeIf { it.isNotEmpty() }
    val queue = latestSongs ?: songs.map(withAlbum)
    if (queue.isEmpty()) return
    val marked = if (preview) queue.asTypeUnknown() else queue
    val queueIndex = selected?.let { tapped ->
        marked.indexOfFirst { it.contextEntryKey() == tapped.contextEntryKey() }
    }?.takeIf { it >= 0 } ?: index
    playFrom(
        marked,
        queueIndex.coerceIn(marked.indices),
        com.music.bitchord.playback.QueueSource(
            page.title,
            com.music.bitchord.data.model.PlaybackSourceType.BROWSE,
            page.browseId,
        ),
        useBounded,
        latestPage?.continuation ?: page.continuation,
    )
}

/** Shuffle starts with what is newest on the page, then lets the service continue. */
internal suspend fun koShuffleDetailSongs(
    page: com.music.bitchord.data.model.DetailPage,
    loaded: List<Song>,
    isCurrentRequest: () -> Boolean,
    takePlaybackContext: (String) -> com.music.bitchord.data.model.DetailPage?,
    withAlbum: (Song) -> Song,
    playFrom: (List<Song>, Int, com.music.bitchord.playback.QueueSource, Boolean, String?) -> kotlinx.coroutines.Job?,
) {
    // A refresh that landed while the screen was composing has already replaced
    // the view model's page; shuffle what is newest, not the list the button was
    // drawn with.
    val latestPage = takePlaybackContext(page.browseId)
    if (!isCurrentRequest()) return
    val preview = latestPage?.songsArePreview == true
    val newest = (latestPage?.songs as? com.music.bitchord.data.model.UiState.Success)?.data
        ?.takeIf { it.isNotEmpty() }
    val rows = (newest ?: loaded).map(withAlbum)
    if (rows.isEmpty()) return
    val queue = if (preview) rows.asTypeUnknown() else rows
    com.music.bitchord.playback.QueueShuffle.enableForNextQueue()
    val remote =
        (page.type == com.music.bitchord.data.model.BrowseType.ALBUM ||
            page.type == com.music.bitchord.data.model.BrowseType.PLAYLIST) &&
            !page.browseId.startsWith("local:")
    val continuation = latestPage?.continuation ?: page.continuation
    val bounded = remote && !preview &&
        continuation != null &&
        !com.music.bitchord.playback.cast.CastController.state.value.casting
    com.music.bitchord.data.TrackLog.i(
        "BitChord",
        "queue: shuffle start n=${queue.size} " +
            "pending pages=${if (bounded) 1 else 0}",
    )
    playFrom(
        queue,
        queue.indices.random(),
        com.music.bitchord.playback.QueueSource(
            page.title,
            com.music.bitchord.data.model.PlaybackSourceType.BROWSE,
            page.browseId,
        ),
        bounded,
        if (bounded) continuation else null,
    )?.join()
}
