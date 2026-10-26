package com.music.bitchord.data

import com.music.bitchord.data.lyrics.LyricsSyncReport
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.TrackSummaryFormat
import com.music.bitchord.playback.audio.SilenceSkipStats
import com.music.bitchord.playback.smart.SyncTestMode
import java.util.Locale

/** What the lyrics engine applied to the track that is playing. */
data class LyricsFacts(
    val provider: String,
    val sync: String,
    val shiftMs: Long?,
    val matchPercent: Int?,
    val manualOffsetMs: Long,
)

/**
 * The facts a per-track summary needs from outside the playback service: which
 * lyric provider answered, how far its timing was moved, and the moment the
 * listener asked to mark.
 *
 * The playback service already owns the route, first sound and played time; the
 * lyrics engine owns the rest. Both write here rather than growing a callback
 * from one to the other, and the summary is assembled from whatever the two
 * have said by the time the track ends. Nothing here makes a request or runs on
 * a timer — every method is called from an event that was already happening.
 */
object TrackDiagnostics {

    private const val MAX_TRACKS = 64

    private val facts = LinkedHashMap<String, LyricsFacts>()
    private val lastSyncLine = HashMap<String, String>()

    /** Records the timing the engine just applied, and logs it once per change. */
    fun noteLyricsSync(
        videoId: String,
        provider: String,
        base: String,
        sync: String,
        shiftMs: Long?,
        matchPercent: Int?,
        biasMs: Long,
        manualOffsetMs: Long,
        video: Boolean,
        /** Whether a downloaded file's sync is stored for these lyrics. */
        stored: Boolean = false,
        /** Why a downloaded file's sync is (or was) re-checked: none/unverified/unknown-provider/low-match/done. */
        recheck: String = "none",
    ) {
        if (videoId.isBlank()) return
        synchronized(this) {
            if (facts.size >= MAX_TRACKS && !facts.containsKey(videoId)) {
                facts.remove(facts.keys.first())
            }
            facts[videoId] = LyricsFacts(provider, sync, shiftMs, matchPercent, manualOffsetMs)
        }
        val line = buildString {
            append("lyrics-sync: ").append(videoId)
            append(" source=").append(provider.ifBlank { "none" })
            append(" base=").append(base)
            append(" shift=").append(shiftMs ?: "-").append("ms")
            append(" match=").append(matchPercent ?: "-").append('%')
            append(" bias=").append(biasMs).append("ms")
            append(" manual=").append(manualOffsetMs).append("ms")
            append(" stored=").append(if (stored) "y" else "n")
            append(" recheck=").append(recheck)
            append(" silenceSkipMs=").append(SilenceSkipStats.skippedMs)
            append(" video=").append(if (video) "y" else "n")
        }
        val changed = synchronized(this) {
            if (lastSyncLine[videoId] == line) {
                false
            } else {
                if (lastSyncLine.size >= MAX_TRACKS && !lastSyncLine.containsKey(videoId)) {
                    lastSyncLine.remove(lastSyncLine.keys.first())
                }
                lastSyncLine[videoId] = line
                true
            }
        }
        if (changed) TrackLog.i("BitChord", line, about = videoId)
    }

    fun lyricsFor(videoId: String): LyricsFacts? = synchronized(this) { facts[videoId] }

    /**
     * The listener's "this line is being sung now" report. Unlike [mark], it
     * names the line the panel was showing and the moment it was tapped, so a
     * run of them says whether the error is steady or drifting.
     */
    fun reportSync(
        videoId: String,
        line: Int,
        total: Int,
        shownMs: Long,
        tappedMs: Long,
        source: String,
        base: String,
        shiftMs: Long?,
        manualMs: Long,
    ) {
        if (videoId.isBlank()) return
        TrackLog.i(
            "BitChord",
            LyricsSyncReport.line(
                videoId = videoId,
                line = line,
                total = total,
                shownMs = shownMs,
                tappedMs = tappedMs,
                source = source,
                base = base,
                shiftMs = shiftMs,
                manualMs = manualMs,
                playing = SyncTestMode.playingId(videoId),
            ),
            about = videoId,
        )
    }

    /**
     * The listener's "report a problem" mark: the playing moment, the lyric
     * line that was under it, and what the engine believed it was playing.
     */
    fun mark(
        song: Song,
        positionMs: Long,
        lyricIndex: Int,
        lyricTimeMs: Long,
        sync: String,
    ) {
        TrackLog.i(
            "BitChord",
            TrackSummaryFormat.mark(
                id = song.videoId,
                title = song.title,
                positionMs = positionMs,
                lyricIndex = lyricIndex,
                lyricTimeMs = lyricTimeMs,
                video = song.isVideo,
                source = sourceLabel(song),
                sync = sync,
                silenceSkipMs = SilenceSkipStats.skippedMs,
            ),
            about = song.videoId,
        )
    }

    /** The queue origin as one short token: `kind:id`, or just the kind. */
    fun sourceLabel(song: Song?): String {
        if (song == null) return "none"
        val kind = song.playbackSourceType?.name?.lowercase(Locale.US)
            ?: if (song.radioName != null) {
                PlaybackSourceType.QUEUE.name.lowercase(Locale.US)
            } else {
                "queue"
            }
        val id = song.playbackSourceId?.takeIf { it.isNotBlank() }
        // A search's id is the query itself, and a log that leaves the phone
        // must not carry what someone typed. Its length is enough to tell a
        // pasted paragraph from a title.
        if (song.playbackSourceType == PlaybackSourceType.SEARCH && id != null) {
            return "$kind:len=${id.length}"
        }
        return if (id == null) kind else "$kind:$id".take(MAX_SOURCE_CHARS)
    }

    /** Which kind of timing is on screen, in the summary's vocabulary. */
    fun syncLabel(base: String, manualOffsetMs: Long): String = when {
        manualOffsetMs != 0L && base == "none" -> "manual"
        base == "ai" -> "ai"
        base == "measured" -> "measured"
        else -> "none"
    }

    private const val MAX_SOURCE_CHARS = 80
}
