package com.music.bitchord.ko

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.smart.HqVerificationStore
import kotlin.math.abs

/**
 * [KO] hq-lossless (review NR-2)
 *
 * Whether automix may open the next song's verified lossless copy directly in
 * the standby player.
 *
 * Every number the blend uses for the incoming side (the cue it opens at, its
 * beat grid, its mix-in point) was measured on the YouTube copy
 * (TrackAnalyzer analyses only the canonical YouTube rendition). The lossless
 * copy runs on its own clock, `candidate = offset + rate x youtube`
 * ([com.music.bitchord.playback.HqSwapPolicy.targetPositionMs]). Opened at the
 * YouTube cue, a copy with an offset starts early or late, its beats sit off
 * the outgoing song's, and its first word can be clipped.
 *
 * So the copy is opened ahead only when its clock is YouTube's within what a
 * blend cannot hear. Anything else keeps the YouTube copy in the standby; the
 * ordinary mid-song upgrade, which maps the position through the verdict
 * ([com.music.bitchord.playback.HqSwapPolicy.swapStartMs]), still moves it to
 * the lossless copy after the blend. A copy with no stored verdict has no known
 * clock and is not opened ahead either.
 */
internal object KoLosslessAhead {
    /** Below one 60 fps frame; a cue or beat this far off is not heard in a blend. */
    const val MAX_OFFSET_MS = 30L

    /** 0.1%: 30 ms of drift by 30 s into the song, the far end of a long blend. */
    const val MAX_RATE_ERROR = 0.001

    fun cueSafe(verified: HqVerificationStore.Verified?): Boolean {
        val v = verified ?: return false
        if (!v.rate.isFinite()) return false
        return abs(v.offsetMs) <= MAX_OFFSET_MS && abs(v.rate - 1.0) <= MAX_RATE_ERROR
    }

    /**
     * Review SW7A-1: whether read-ahead may pin a verified copy as the next
     * row's first sound. Every open of the plain key is served by the pin, so
     * refusing only in the standby ([cueSafe] above) still played the pinned
     * copy at the YouTube cue. Under Automix ([automix]: the only mode that
     * cues the incoming side off zero) an off-clock lossless copy is not
     * pinned at all; the mid-song upgrade maps it after the blend.
     */
    fun mayPinAhead(automix: Boolean, lossless: Boolean, verified: HqVerificationStore.Verified?): Boolean =
        !automix || !lossless || cueSafe(verified)

    /**
     * Review SW7A-2: whether every stored verdict for a song is on YouTube's
     * clock. None stored is no known clock, the same as [cueSafe] of null.
     */
    fun allCueSafe(verdicts: List<HqVerificationStore.Verified>): Boolean =
        verdicts.isNotEmpty() && verdicts.all(::cueSafe)

    /**
     * Review SW7A-2: whether arming the standby defers the song's plain key.
     * Decided on the verdicts alone, with no look at the cache: deferring a key
     * that has no complete copy behind it changes nothing, because a copy
     * completed later in this play is deferred by its own fetch
     * ([com.music.bitchord.ko.KoLosslessCache.prefetchVerifiedRendition],
     * [com.music.bitchord.ko.KoLosslessCache.downloadVerifiedRendition]).
     */
    fun defersPlainKey(automix: Boolean, verdicts: List<HqVerificationStore.Verified>): Boolean =
        automix && !allCueSafe(verdicts)
}

/** [KoLosslessAhead.mayPinAhead] for [stream] as [videoId]'s next-row pin, logged when refused. */
internal fun koMayPinAhead(context: Context, videoId: String, stream: SourceStream): Boolean {
    val sourceId = stream.sourceConfigId
    val lossless = sourceId != null &&
        SourceRegistry.config(sourceId)?.kind != SourceKind.YOUTUBE &&
        HqQuality.isLosslessTier(stream.format)
    if (!lossless) return true
    val verified = HqVerificationStore.get(context, videoId, sourceId, stream.trackId)
    if (KoLosslessAhead.mayPinAhead(AppSettings.smartFadeEnabled.value, lossless = true, verified)) return true
    TrackLog.d(
        "BitChord",
        "hq-ahead: $videoId not pinned for Automix (offset=${verified?.offsetMs} rate=${verified?.rate})",
        about = videoId,
    )
    return false
}

/**
 * nextprep B4: the Direct Source Stream proof's clock check. A proved addon or
 * Telegram copy becomes the next row's pin with no [koMayPinAhead] (prefetchDirect,
 * resolveDirect), so under Automix an off-clock FLAC opened at the YouTube cue.
 * A song already audible is not cued by the blend ([audible]), so it passes.
 */
internal fun koDirectOnClock(
    videoId: String,
    stream: SourceStream,
    verified: HqVerificationStore.Verified,
    audible: Boolean,
    automix: Boolean = AppSettings.smartFadeEnabled.value,
): Boolean {
    if (audible) return true
    val lossless = stream.sourceConfigId?.let { SourceRegistry.config(it)?.kind != SourceKind.YOUTUBE } == true &&
        HqQuality.isLosslessTier(stream.format)
    if (KoLosslessAhead.mayPinAhead(automix, lossless, verified)) return true
    TrackLog.d(
        "BitChord",
        "direct: $videoId off YouTube's clock under Automix (offset=${verified.offsetMs} rate=${verified.rate}); " +
            "starts on YouTube, the mid-song swap maps it",
        about = videoId,
    )
    return false
}

/**
 * Review SW7A-2: a complete lossless copy from an earlier play takes the plain
 * key ([com.music.bitchord.ko.KoLosslessCache.completeUpgradeRendition]) with
 * no pin and no clock check, so the standby opened it at the YouTube cue.
 * Under Automix, a song whose verdicts are not all on YouTube's clock keeps
 * its plain key until the queue moves on, the prefetch deferral's lifecycle.
 *
 * Runs on the player thread when the standby arms, so it reads only the
 * in-memory verdicts ([KoLosslessAhead.defersPlainKey]); asking the cache for
 * the complete copy took the SimpleCache lock a loader may hold and read a
 * file head (review 11-18).
 */
internal fun koDeferOffClockUpgradeKey(context: Context, videoId: String) {
    if (!AppSettings.smartFadeEnabled.value) return
    val verdicts = HqVerificationStore.entries(context).filter { it.youtubeId == videoId }.map { it.verified }
    if (KoLosslessAhead.defersPlainKey(automix = true, verdicts)) AudioCache.deferUpgradeKey(videoId)
}
