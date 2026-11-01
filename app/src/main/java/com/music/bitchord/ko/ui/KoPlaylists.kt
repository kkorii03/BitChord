package com.music.bitchord.ko.ui

import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.stats.SmartPlaylist
import com.music.bitchord.data.stats.SmartPlaylists
import com.music.bitchord.ui.MainViewModel

/**
 * Renames a playlist that lives only on this device, the counterpart to
 * renamePlaylist for one made without an account (a Spotify import, or
 * one saved while signed out). The store owns the change; the open page,
 * if any, is restated here.
 */
internal fun MainViewModel.koRenameLocalPlaylist(browseId: String, title: String) {
    val name = title.trim()
    if (name.isBlank()) return
    LocalPlaylistStore.renamePlaylist(browseId, name)
    _detailStack.value = _detailStack.value.map { page ->
        if (page.browseId == browseId) page.copy(title = name) else page
    }
}

/** Deletes a device-only playlist, closing its page when it is the one open. */
internal fun MainViewModel.koDeleteLocalPlaylist(browseId: String) {
    LocalPlaylistStore.deletePlaylist(browseId)
    val before = _detailStack.value.size
    _detailStack.value = _detailStack.value.filterNot { it.browseId == browseId }
    if (_detailStack.value.size != before) navigator.onDetailStackShrunk(_detailStack.value.size)
}

/**
 * One of the playlists made from this device's listening. The liked list
 * is read whole from YouTube when it can be, so a long Liked Music still
 * finds its unplayed songs; the library's first page stands in offline.
 */
internal suspend fun MainViewModel.koSmartPlaylistState(kind: SmartPlaylist): UiState<List<Song>> {
    val liked = if (kind == SmartPlaylist.LIKED_UNPLAYED) {
        if (!signedIn.value) return UiState.Error(text(R.string.smart_playlist_sign_in))
        YtMusicRepository.allSongs(YtMusicRepository.LIKED_MUSIC).getOrNull()
            ?: (_library.value as? UiState.Success)?.data?.likedSongs.orEmpty()
    } else {
        emptyList()
    }
    val songs = runCatching { SmartPlaylists.songs(kind, liked) }.getOrDefault(emptyList())
    return if (songs.isEmpty()) UiState.Error(text(R.string.smart_playlist_empty)) else UiState.Success(songs)
}
