package com.music.bitchord.ko

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.download.Downloads
import com.music.bitchord.ko.downloads.KoDownloadRecords
import com.music.bitchord.playback.StreamChoice

/**
 * [KO] x1120-nextdl: the downloaded file a video row's lyric timing should hear.
 *
 * A downloaded video row plays its file ([com.music.bitchord.playback.PlayerConnection]
 * hands a video row its file whenever one is saved and not proved wrong), but the AI
 * timing ask heard it through a bare Song with no file, so it resolved and pulled the
 * YouTube stream: 11-20 phone dA4qZ4qJGP4 (16:34:49, a 10.4 s resolve and 3.3 MB) and
 * lYjm_KXe7MU (17:06:46, 8.8 s, 3.9 MB), both rows that then played their opus file.
 *
 * Only a file of the row's own sound on YouTube's clock: a file cut from another upload
 * is [com.music.bitchord.playback.smart.LyricsTiming.fileOfAnotherUpload]'s, a quality
 * copy (an HQ source) or a chosen stream plays something else and keeps the old path.
 */
object KoVideoFileHearing {

    /** Whether a video row's AI ask hears its saved file rather than its stream. */
    internal fun hearsFile(
        isVideo: Boolean,
        saved: Boolean,
        wrongRecording: Boolean,
        hqSourceConfigId: String?,
        soundSourceVideoId: String?,
        videoId: String,
        streamChosen: Boolean,
    ): Boolean =
        isVideo && saved && !wrongRecording && !streamChosen && hqSourceConfigId.isNullOrBlank() &&
            (soundSourceVideoId == null || soundSourceVideoId == videoId)

    /** The saved file's uri for [videoId] when [hearsFile] allows it and the file is there, else null. */
    suspend fun fileFor(context: Context, videoId: String, isVideo: Boolean): String? {
        if (!isVideo) return null
        val meta = KoDownloadRecords.savedMetadataFor(videoId) ?: return null
        val allowed = hearsFile(
            isVideo = true,
            saved = true,
            wrongRecording = meta.wrongRecording,
            hqSourceConfigId = meta.hqSourceConfigId,
            soundSourceVideoId = meta.soundSourceVideoId,
            videoId = videoId,
            streamChosen = StreamChoice.soundsSubstitute(videoId) || StreamChoice.soundsYouTube(videoId),
        )
        if (!allowed) return null
        // The same check playback makes: not proved wrong, and the file still there.
        val uri = Downloads.savedUri(context, videoId)?.toString() ?: return null
        TrackLog.d(TAG, "song-hear: hearing the downloaded file, not the stream", videoId)
        return uri
    }

    private const val TAG = "KoVideoFileHearing"
}
