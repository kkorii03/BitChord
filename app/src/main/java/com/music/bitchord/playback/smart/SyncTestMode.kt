package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsWordAlign
import com.music.bitchord.data.lyrics.SyncDump
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.playback.StreamChoice
import java.io.File

/**
 * The app side of sync-test mode: off unless a `sync-test.flag` file exists in
 * the app's own external folder. While it is on, every final lyric timing is
 * written to logcat as a [SyncDump] block, and the WAV a song check decodes is
 * kept beside the flag for scoring.
 *
 * The flag is read once per song, not per timing refresh: a file added or
 * removed mid-song takes effect on the next track, as the test harness expects.
 * With the flag absent nothing is logged and nothing is written.
 */
object SyncTestMode {

    private const val TAG = "LyricsTiming"
    private const val FLAG_NAME = "sync-test.flag"
    private const val WAV_DIR = "synctest"

    private var checkedVideoId: String? = null
    private var checkedEnabled = false

    /** Kept from the last [enabled] call, for [keepCandidate], which runs without one. */
    @Volatile
    private var appContext: Context? = null

    /** The last block written, so a refresh that changes nothing is not logged twice. */
    private var lastSignature: String? = null

    /**
     * At app start: with the flag present, the lyrics search gets the slow
     * test device's longer deadline (see [com.music.bitchord.data.lyrics.LyricsRepository.allowSlowTestDevice]).
     */
    fun applyTestAllowances(context: Context) {
        val on = runCatching { externalRoot(context)?.let { File(it, FLAG_NAME).isFile } == true }.getOrDefault(false)
        if (!on) return
        com.music.bitchord.data.lyrics.LyricsRepository.allowSlowTestDevice()
        TrackLog.i(TAG, "sync-test: lyrics search deadline ${com.music.bitchord.data.lyrics.LyricsRepository.lookupDeadline} ms")
    }

    /** Whether sync-test mode is on for [videoId]; the flag file is checked once per song. */
    fun enabled(context: Context, videoId: String): Boolean = synchronized(this) {
        appContext = context.applicationContext
        if (checkedVideoId != videoId) {
            checkedVideoId = videoId
            checkedEnabled = runCatching {
                externalRoot(context)?.let { File(it, FLAG_NAME).isFile } == true
            }.getOrDefault(false)
        }
        checkedEnabled
    }

    /**
     * Writes one block for the timing just applied. Called on every refresh;
     * a block identical to the last one is not written twice, so the measure
     * loop and a change event landing together cannot double-log it.
     */
    fun report(
        context: Context,
        videoId: String,
        source: String,
        base: String,
        shiftMs: Long,
        matchPercent: Int?,
        lines: List<LyricLine>,
        heardWords: List<LyricsWordAlign.Heard>,
    ) {
        if (!enabled(context, videoId)) return
        runCatching {
            val dump = SyncDump.dump(
                videoId = videoId,
                playing = playingId(videoId),
                source = source,
                base = base,
                shiftMs = shiftMs,
                matchPercent = matchPercent,
                lines = lines,
                heardWords = heardWords,
            )
            val signature = dump.joinToString("\n")
            synchronized(this) {
                if (lastSignature == signature) return@synchronized
                lastSignature = signature
                // Written inside the lock so two refreshes cannot interleave one
                // block's lines with another's.
                dump.forEach { TrackLog.i(TAG, it, videoId) }
            }
        }
    }

    /** Keeps [wav] as `synctest/<videoId>.wav`; one file per song, replaced on a later check. */
    fun saveWav(context: Context, videoId: String, wav: ByteArray) {
        if (!enabled(context, videoId)) return
        write(context, videoId, wav)
    }

    /**
     * Keeps the head of a downloaded hi-res candidate as `synctest/<id>.wav`,
     * where `<id>` is what [playingId] logs once that copy plays. The song
     * check's WAV is always YouTube's copy, so without this a substitute's
     * lyrics could only be scored on the wrong clock. Decodes the head a second
     * time; only test runs (flag file present) pay for it.
     */
    fun keepCandidate(sourceConfigId: String?, trackId: String?, head: File) {
        val context = appContext ?: return
        if (sourceConfigId == null || trackId == null) return
        val on = runCatching { externalRoot(context)?.let { File(it, FLAG_NAME).isFile } == true }.getOrDefault(false)
        if (!on) return
        val wav = VideoAudio.wavOfFile(head) ?: return
        write(context, "${SourceTrackKeys.PREFIX}$sourceConfigId${SourceTrackKeys.SEPARATOR}$trackId", wav)
    }

    /**
     * Keeps the opening of a downloaded file [localUri] as `synctest/<videoId>.wav`
     * — the same name a song check's WAV takes — unless one is already there.
     */
    suspend fun keepLocalHead(context: Context, videoId: String, localUri: String) {
        if (!enabled(context, videoId)) return
        val existing = externalRoot(context)?.let { File(File(it, WAV_DIR), "${safeName(videoId)}.wav") }
        if (existing?.isFile == true) return
        val song = Song(videoId = videoId, title = "", artist = "", thumbnailUrl = null, localUri = localUri)
        val wav = VideoAudio.songWav(context, song) ?: return
        write(context, videoId, wav)
    }

    private fun write(context: Context, id: String, wav: ByteArray) {
        runCatching {
            val root = externalRoot(context) ?: return@runCatching
            val dir = File(root, WAV_DIR).apply { mkdirs() }
            val file = File(dir, "${safeName(id)}.wav")
            val temporary = File(dir, "${file.name}.tmp")
            try {
                temporary.writeBytes(wav)
                if (!temporary.renameTo(file)) {
                    file.delete()
                    temporary.renameTo(file)
                }
            } finally {
                temporary.delete()
            }
        }
    }

    /**
     * The recording actually being played: a substituted source's own track id
     * in the queue's `src:<config>::<track>` shape, or the video id when the
     * YouTube copy is the one playing.
     */
    private fun playingId(videoId: String): String {
        if (!StreamChoice.isSubstitute(videoId)) return videoId
        val choice = StreamChoice.of(videoId) ?: return videoId
        val sourceId = choice.sourceConfigId ?: return videoId
        val trackId = choice.trackId ?: return videoId
        return "${SourceTrackKeys.PREFIX}$sourceId${SourceTrackKeys.SEPARATOR}$trackId"
    }

    private fun externalRoot(context: Context): File? =
        context.applicationContext.getExternalFilesDir(null)

    /** A video id that is safe as a file name; ordinary ids (YouTube or `src:…::…`) pass through. */
    private fun safeName(videoId: String): String =
        videoId.map { char ->
            // ':' too: a substitute's `src:<config>::<track>` id broke the emulator
            // run's artifact upload (11-02), which refuses colons in paths.
            if (char == '/' || char == '\\' || char == '\u0000' || char == ':') '_' else char
        }.joinToString("").ifBlank { "song" }
}
