package com.music.bitchord.ko

/**
 * [KO] dupdl: a download of the very file the player is filling into the song
 * cache right now takes the player's copy instead of a second whole GET.
 *
 * 11-19 02:21 log, Cruel Summer aC9HkZW2hZk (37 MB FLAC on metered LTE): the
 * player opened the addon's stream at 02:21:20 and filled the cache by 02:21:44;
 * a queued download of the same song resolved the same addon URL at 02:21:21
 * and fetched all 37 MB again by 02:21:32.
 *
 * Only a fill that is happening now: the player holds the song's entry, the
 * entry is not whole yet, and the stream it is pinned to is the URL the
 * download resolved, before and after the wait. A whole entry already on disk
 * has no record of which URL filled it (an earlier session's file, another
 * source's), so it is never taken here. The wait is the player's own release
 * of the entry (an event, no timer); the copy is read from disk alone and its
 * container must be the one the download files. Anything else: null, and the
 * download fetches as before.
 */
internal object KoPlayersFill {

    suspend fun <T> copyOf(
        url: String,
        extension: String,
        pinnedUrl: () -> String?,
        playerHolds: () -> Boolean,
        whole: suspend () -> T?,
        extensionOf: (T) -> String,
        awaitRelease: suspend () -> Unit,
        log: (String) -> Unit,
    ): T? {
        if (url.isBlank() || pinnedUrl() != url) return null
        if (!playerHolds()) return null
        // Whole already: filled before this play, by a URL nothing records.
        if (whole() != null) return null
        log("download: waits for the player's fill of the same file")
        awaitRelease()
        if (pinnedUrl() != url) {
            log("download: the player moved to another stream; fetching")
            return null
        }
        val copy = whole()
        if (copy == null) {
            log("download: the player's fill is not whole; fetching")
            return null
        }
        if (extensionOf(copy) != extension) {
            log("download: the player's fill is .${extensionOf(copy)}, not .$extension; fetching")
            return null
        }
        log("download: copying the player's fill; no second fetch")
        return copy
    }
}
