package com.music.bitchord.ko

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.spotify.LocalPlaylist

internal data class KoBrowsePlaylist(
    val local: LocalPlaylist?,
    val remote: UserPlaylist?,
    val playlist: UserPlaylist?,
)

/**
 * A playlist saved only on this device has no account owner to ask about, but
 * it is still the listener's to rename and delete. Its row is built here so it
 * reaches the same sheet the account's own playlists do.
 */
internal fun koBrowsePlaylist(
    browseId: String?,
    signedIn: Boolean,
    ownedPlaylists: Map<String, Boolean>,
    playlists: List<UserPlaylist>,
    localPlaylists: List<LocalPlaylist>,
    context: Context,
): KoBrowsePlaylist {
    val local = browseId?.let { id ->
        localPlaylists.firstOrNull { it.browseId == id }
    }
    val remote = browseId
        ?.takeIf { signedIn && ownedPlaylists[it] == true }
        ?.let { id -> playlists.firstOrNull { it.browseId == id } }
    val playlist = remote ?: local?.let {
        UserPlaylist(
            playlistId = it.browseId,
            title = it.title,
            subtitle = context.getString(R.string.local_playlist_subtitle, it.songs.size),
            thumbnailUrl = it.songs.firstOrNull { song -> !song.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
        )
    }
    return KoBrowsePlaylist(local = local, remote = remote, playlist = playlist)
}
