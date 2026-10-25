package com.music.bitchord.data.model

import com.music.bitchord.data.cover.videoThumbnailUrl

/**
 * Where a row's sound actually comes from.
 *
 * The old menu treated every `localUri` alike and hid the account rows for a
 * finished download as well as for the listener's own file. Those are not the
 * same thing: a download still carries the YouTube id it was fetched by, so
 * its library entry, rating, playlist membership and share link all exist.
 * A device file or a WebDAV/SMB row carries no such identity.
 */
enum class SongOrigin {
    /** Not on this device: resolved and streamed from a source. */
    STREAM,

    /** A YouTube track this app downloaded; still has its catalogue identity. */
    DOWNLOADED_YOUTUBE,

    /** The listener's own file, or a file library with no YouTube id. */
    LOCAL_FILE,
}

/**
 * The one place a row's identity is decided.
 *
 * A YouTube id is the same eleven characters the artwork helper accepts, which
 * is also what every download record keeps. A local MediaStore row uses its
 * content URI as `videoId`, and SMB/WebDAV rows use their own prefixed ids, so
 * neither can pass this test by accident.
 */
object SongOrigins {

    fun hasYouTubeIdentity(videoId: String?): Boolean = videoThumbnailUrl(videoId) != null

    fun of(song: Song): SongOrigin = when {
        song.localUri == null -> SongOrigin.STREAM
        hasYouTubeIdentity(song.videoId) -> SongOrigin.DOWNLOADED_YOUTUBE
        else -> SongOrigin.LOCAL_FILE
    }

    fun isDownloadedYouTube(song: Song): Boolean = of(song) == SongOrigin.DOWNLOADED_YOUTUBE

    /** A device file or a remote file library row — never a finished download. */
    fun isLocalFile(song: Song): Boolean = of(song) == SongOrigin.LOCAL_FILE

    /**
     * Whether the account's own actions belong on this row's menu.
     *
     * A streamed track and a downloaded YouTube track do; a plain local file
     * has no library entry, rating or link to offer.
     */
    fun showsAccountActions(song: Song): Boolean = of(song) != SongOrigin.LOCAL_FILE
}
