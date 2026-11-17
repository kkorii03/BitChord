package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.playback.LocalTimingPolicy
import com.music.bitchord.playback.smart.LyricsTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update

/**
 * [KO] U3: the sheet the lock screen and the car / Bluetooth subtitle show is the one
 * the lyrics screen chose.
 *
 * The playback service looks the lyrics up once at track start. Everything the lyrics
 * screen decides afterwards for the same video — throwing out another song's sheet on
 * the words heard and reloading, [com.music.bitchord.playback.smart.SheetChoice]
 * picking a sheet the words fit, a provider picked by hand, a late upgrade — used to
 * stay on the screen only, so the lock screen kept the wrong song's lyrics for the rest
 * of the track. The screen now says what it put up ([chosen]); the service job follows
 * it for the same video id ([follow]) through its own timing path, on events only.
 */
object KoLockSheet {
    /**
     * What the lyrics screen shows for [videoId]. [lines] null: it threw [rejected] out
     * as another song's and has none (yet). [fromFile] / [recheckOverride] say where the
     * sheet came from, as the service reads them for its own sheet (see [Sheet]).
     */
    data class Choice(
        val videoId: String,
        val lines: List<LyricLine>?,
        val source: LyricsSource? = null,
        val fromFile: Boolean = false,
        val recheckOverride: Boolean = false,
        val rejected: List<LyricLine>? = null,
    )

    private val _chosen = MutableStateFlow<Choice?>(null)
    val chosen: StateFlow<Choice?> = _chosen.asStateFlow()

    /** The lyrics screen put [lines] on screen for [videoId]. */
    fun chose(
        videoId: String,
        lines: List<LyricLine>,
        source: LyricsSource?,
        fromFile: Boolean = false,
        recheckOverride: Boolean = false,
    ) {
        _chosen.value = Choice(videoId, lines, source, fromFile, recheckOverride)
    }

    /** The lyrics screen threw [lines] out as another song's for [videoId]; nothing until it shows the next. */
    fun rejected(videoId: String, lines: List<LyricLine>) {
        _chosen.value = Choice(videoId, lines = null, rejected = lines)
    }

    /** A choice for any other video is never shown: dropped as soon as [videoId] is loaded. */
    fun forgetOtherThan(videoId: String) {
        _chosen.update { if (it != null && it.videoId != videoId) null else it }
    }

    /**
     * The choice the service should adopt over the sheet it shows now ([shownRaw], before
     * timing), or null to keep its own: no choice, a choice for another video, the same
     * lines, or a rejection of a sheet it is not showing (that one nobody judged).
     */
    fun pick(videoId: String, shownRaw: List<LyricLine>?, chosen: Choice?): Choice? {
        if (chosen == null || chosen.videoId != videoId) return null
        if (chosen.lines == shownRaw) return null
        if (chosen.lines == null && chosen.rejected != shownRaw) return null
        return chosen
    }

    /** A sheet the service shows for one track, and the timing rules its origin gives it. */
    class Sheet(
        val raw: List<LyricLine>?,
        val source: LyricsSource?,
        fromFile: Boolean,
        recheckOverride: Boolean,
        isVideo: Boolean,
    ) {
        val timed: Boolean = raw != null && raw.any { it.timeMs > 0 }

        // The same rules the lyrics screen keeps: a song's own file is timed
        // for it (a correction by hand only); a video's lyrics — streamed,
        // or carried in its download — take Gemini's answer, and the
        // measurement when they are known to be the record's. A re-checked
        // download is timed for that file, so the lock screen, notification
        // and car subtitle get the same correction as the lyrics screen.
        private val sourceTimedToVideo: Boolean = LyricsTiming.timedToVideo(source)

        // A substituted stream is a different recording under the same
        // video id. Same rule as the lyrics screen since ko-112: the answer
        // is not refused up front; LyricsTiming.timing places it on the
        // copy's clock or drops it (11-06 cCfPDrRQp9k: the lock screen said
        // not-allowed while the screen applied what it could). This path
        // only reads the store; measuring a substitute stays refused by
        // syncWanted and the next-prep gate.
        val timeable: Boolean = LocalTimingPolicy.downloadedTimingTimeable(
            timed = timed,
            fromFile = fromFile,
            isVideo = isVideo,
            recheckOverride = recheckOverride,
            sourceTimedToVideo = sourceTimedToVideo,
        )
        val recordTimed: Boolean = timeable && (!fromFile || source != null) && !sourceTimedToVideo
    }

    private class Event(val retime: Boolean, val choice: Choice?)

    /**
     * For as long as the track is current (the caller's job): re-times the sheet showing
     * on every timing change for [videoId] (as before, only a timed sheet), and replaces
     * it with the lyrics screen's choice for the same id. One collector, so a re-timing
     * and a replacement never publish over each other.
     */
    suspend fun follow(
        videoId: String,
        isVideo: Boolean,
        first: Sheet,
        changes: Flow<String> = LyricsTiming.changes,
        choices: Flow<Choice?> = chosen,
        publish: suspend (Sheet) -> Unit,
    ) {
        var sheet = first
        merge(
            changes.filter { it == videoId }.map { Event(retime = true, choice = null) },
            choices.map { Event(retime = false, choice = it) },
        ).collect { event ->
            if (event.retime) {
                if (sheet.timed) publish(sheet)
                return@collect
            }
            val next = pick(videoId, sheet.raw, event.choice) ?: return@collect
            sheet = Sheet(next.lines, next.source, next.fromFile, next.recheckOverride, isVideo)
            TrackLog.i(
                "BitChord",
                "lock-sheet: screen's sheet ${next.source?.label ?: if (next.lines == null) "none (rejected)" else "file"} " +
                    "lines=${next.lines?.size ?: 0}",
                about = videoId,
            )
            publish(sheet)
        }
    }
}
