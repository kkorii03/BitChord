package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongOrigins

/**
 * [KO] C6a-1: which account-style rows the song menu draws.
 *
 * A plain device file has no YouTube identity, but v1.9 keeps its own device
 * playlists and Liked list for it (OfflinePlaylists). The menu used to hide
 * Like / Add to playlist / Remove from playlist for every local file, so a
 * device playlist could never be filled. Library and Dislike stay account-only.
 */
internal object KoSongMenuRows {

    /** Rows the host sends to the device playlist picker (content:// or file://). */
    fun isDevicePlaylistRow(song: Song): Boolean =
        song.localUri != null &&
            (song.videoId.startsWith("content://") || song.videoId.startsWith("file://"))

    /** "Add to playlist": account playlists when signed in, device playlists for a device file. */
    fun showsAddToPlaylist(signedIn: Boolean, song: Song): Boolean =
        if (SongOrigins.isLocalFile(song)) isDevicePlaylistRow(song) else signedIn

    /** The heart: the account's like, or the device Liked list for a local file. */
    fun showsLike(signedIn: Boolean, song: Song): Boolean =
        signedIn || SongOrigins.isLocalFile(song)
}
