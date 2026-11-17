package com.music.bitchord.ko

import android.content.Context
import com.music.bitchord.ko.downloads.KoDownloadRecords
import com.music.bitchord.playback.smart.LyricsTiming
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * [KO] Downloaded files heard, in this app run, to be their own YouTube stream: an
 * accepted match at offset 0 and rate 1 (review LOG6 R2, 11-17).
 *
 * A song-sync answer is kept per sound (stream or file), and the stream's answer
 * reaches the file only when one of them plays on a substitute clock with its own
 * verified map. A plain download (no HQ source) has no such map: both are YouTube's
 * clock, and the answer stayed behind. 11-17 phone FcXRwUsA6xs: -553 ms at 94 % on
 * the stream, then `hq-verify: downloaded file download match=100% offset=0 ->
 * accepted`, the switch to the file, and `base=none ... sibling=no-substitute-clock`.
 * A check that just heard the file as the stream at offset 0 is that map.
 *
 * Memory only, keyed by the file's uri and version, so a replaced file or the next
 * app run carries nothing until it is heard again.
 */
object KoFileSameAsStream {

    /** videoId to the uri and version of its file heard identical. */
    private val files = ConcurrentHashMap<String, String>()

    /**
     * What the download check made of [videoId]'s file at [uri] / [version]: [fresh]
     * when these bytes were just compared and accepted, [ok] when the check passed.
     */
    fun measured(
        videoId: String,
        uri: String,
        version: String,
        fresh: Boolean,
        ok: Boolean,
        offsetMs: Long?,
        rate: Double?,
    ) {
        if (fresh && ok && identical(offsetMs, rate)) {
            if (files.size >= MAX_FILES) files.keys.firstOrNull()?.let { files.remove(it) }
            files[videoId] = key(uri, version)
        } else if (fresh || !ok) {
            files.remove(videoId)
        }
    }

    /** Whether [videoId]'s file at [uri] was heard identical to its stream; [version] is read only when one was. */
    fun heardSame(videoId: String, uri: String?, version: () -> String): Boolean {
        val noted = files[videoId] ?: return false
        return uri != null && noted == key(uri, version())
    }

    /** One straight line through the start: the same master, cut the same way. */
    internal fun identical(offsetMs: Long?, rate: Double?): Boolean =
        offsetMs == 0L && rate != null && rate.isFinite() && abs(rate - 1.0) <= RATE_SLACK

    /** Both answers on YouTube's clock, the file playing, and the file heard to be the stream. */
    internal fun carries(aiClock: LyricsTiming.Clock, playing: LyricsTiming.Clock, playsFile: Boolean, heardSame: Boolean): Boolean =
        playsFile && heardSame && aiClock == LyricsTiming.Clock.YouTube && playing == LyricsTiming.Clock.YouTube

    /** [carries] for [videoId] as [LyricsTiming.timing] sees it, playing [audioSource]. */
    fun carries(
        context: Context,
        videoId: String,
        audioSource: String?,
        aiClock: LyricsTiming.Clock,
        playing: LyricsTiming.Clock,
    ): Boolean {
        val playsFile = audioSource == LyricsTiming.audioSourceKey(videoId, localFile = true) &&
            LyricsTiming.fileOfAnotherUpload(videoId) == null
        if (!carries(aiClock, playing, playsFile, heardSame = true)) return false
        val uri = KoDownloadRecords.savedMetadataFor(videoId)?.uri
        return heardSame(videoId, uri) { KoDownloadRecords.fileVersion(context, uri.orEmpty()) }
    }

    internal fun clearForTest() = files.clear()

    private fun key(uri: String, version: String) = uri + '\u0001' + version

    /** 30 ms over five minutes. */
    private const val RATE_SLACK = 1e-4
    private const val MAX_FILES = 256
}
