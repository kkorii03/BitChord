package com.music.bitchord.data.lyrics

import android.content.Context
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.SyncSafetyPolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android side of [LyricsOverrideFile]: where the one JSON file lives.
 *
 * It sits under the app's own `files` directory, never the cache Android may
 * clear, so a re-checked sheet stays chosen until something explicitly
 * replaces it. All calls do their disk work on [Dispatchers.IO].
 */
object LyricsOverrideStore {

    fun file(context: Context): File = File(context.filesDir, LyricsOverrideFile.FILE_NAME)

    suspend fun read(context: Context, videoId: String): LyricsOverrideFile.Stored? =
        withContext(Dispatchers.IO) {
            if (!SyncSafetyPolicy.overrideReadAllowed(AppSettings.syncSafetySwitches)) {
                null
            } else if (videoId.isBlank()) {
                null
            } else {
                LyricsOverrideFile.read(file(context), videoId)
            }
        }

    suspend fun write(
        context: Context,
        videoId: String,
        lines: List<LyricLine>,
        source: LyricsSource?,
        kind: LyricsOverrideFile.Kind = LyricsOverrideFile.Kind.RECHECK,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!SyncSafetyPolicy.overrideWriteAllowed(AppSettings.syncSafetySwitches)) {
            false
        } else {
            LyricsOverrideFile.write(file(context), videoId, lines, source, kind = kind)
        }
    }

    suspend fun remove(context: Context, videoId: String): Boolean = withContext(Dispatchers.IO) {
        LyricsOverrideFile.remove(file(context), videoId)
    }

    /**
     * Drops an automatic re-check override after its file was replaced or
     * deleted, leaving a listener's own override alone.
     *
     * Not gated on the re-check switches: this only removes an answer that no
     * longer describes the file, and the next play must not show it if the
     * switches are turned on later.
     */
    suspend fun removeRecheck(context: Context, videoId: String): Boolean = withContext(Dispatchers.IO) {
        LyricsOverrideFile.remove(file(context), videoId, LyricsOverrideFile.Kind.RECHECK)
    }
}
