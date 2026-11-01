package com.music.bitchord.playback

import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.download.SavedSongMetadata

/**
 * Assembles the saved-download evidence for a song-version replacement.
 *
 * The shared rule needs the file's own title and upload type, and when its
 * provenance names another upload, that upload's metadata too: only a source
 * recorded as a video is the music video's sound. Both replacement call sites
 * (read-ahead and the in-place swap) ask this same question.
 */
internal object VersionReplacementFilePolicy {

    fun usesDownloadedFile(
        playId: String,
        saved: SavedSongMetadata?,
        source: SavedSongMetadata?,
        catalogDurationMs: Long?,
        online: Boolean,
        mvSoundSuspectMs: Long? = null,
        fileDurationMs: Long? = null,
    ): Boolean = SongVersionPolicy.versionReplacementUsesDownloadedFile(
        playId = playId,
        soundSourceId = saved?.soundSourceVideoId,
        online = online,
        fileDurationMs = fileDurationMs ?: saved?.durationText?.durationMillis(),
        catalogDurationMs = catalogDurationMs,
        title = saved?.title.orEmpty(),
        isVideo = saved?.isVideo,
        typeUnknown = saved?.typeUnknown == true,
        artist = saved?.artist.orEmpty(),
        sourceIsVideo = source?.isVideo,
        sourceTitle = source?.title.orEmpty(),
        mvSoundSuspectMs = mvSoundSuspectMs,
    )
}
