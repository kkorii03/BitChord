package com.music.bitchord.data

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.Downloads
import com.music.bitchord.ko.downloads.wasVideoRow

/**
 * Restores the video flag of a row rebuilt from a store that may predate it.
 *
 * A row saved before the flag was kept reads as a catalogue track, which made
 * song version, video picture and video radio skip it. The download record and
 * the cover marks are the two places that still know, so they fill it back in.
 */
internal fun Song.withStoredVideoFlag(recordedIsVideo: Boolean? = null): Song {
    // A version the listener converted by hand stays converted: isVideoOrigin
    // still says the upload is a video, while isVideo says its audio plays.
    if (isVideoOrigin && !isVideo) return this
    val marked = if (recordedIsVideo == true && !isVideo) {
        copy(isVideo = true, isVideoOrigin = true)
    } else {
        this
    }
    if (marked.isVideo) return marked
    // Read the already-opened snapshot: a UI row must not parse the whole
    // download record on the main thread just to recover one flag.
    if (Downloads.savedMetadataValue()[marked.videoId]?.wasVideoRow() == true) {
        return marked.copy(isVideo = true, isVideoOrigin = true)
    }
    return CoverTitles.restoreVideoFlag(marked)
}
