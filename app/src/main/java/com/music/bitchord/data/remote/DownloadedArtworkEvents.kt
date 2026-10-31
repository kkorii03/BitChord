package com.music.bitchord.data.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The signal that a downloaded track's cover file has just been written.
 *
 * A row reads its cover once, when it is composed. A sidecar or extracted
 * picture that lands after that read used to stay invisible until something
 * else happened to recompose the row — the old timed miss retry only ran when
 * it did. This reports the save itself, by the ids the track is filed under, so
 * only a row showing that track asks again. Event-driven: nothing polls.
 */
object DownloadedArtworkEvents {

    private val _saved = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /** Video ids whose cover file was just saved. */
    val saved: SharedFlow<String> = _saved.asSharedFlow()

    /** Report that the cover for [videoId] has finished writing. */
    fun coverSaved(videoId: String?) {
        if (videoId.isNullOrBlank()) return
        // Retire the remembered answer now, not in the row's collector: the row
        // may be off-screen or recycled when the file lands, and there is no
        // replay to deliver the event when it comes back.
        DownloadedArtworkResolver.invalidateForVideoId(videoId)
        _saved.tryEmit(videoId)
    }

    /** The same, for every id one downloaded file is filed under. */
    fun coverSaved(videoIds: Iterable<String>) {
        videoIds.forEach(::coverSaved)
    }
}
