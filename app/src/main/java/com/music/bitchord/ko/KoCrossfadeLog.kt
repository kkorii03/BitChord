package com.music.bitchord.ko

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.CrossfadeSkipReason
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import java.util.Locale

/**
 * [KO] crossfade-log
 *
 * Fork-only crossfade diagnostics, kept out of upstream's controller. Every
 * line keeps the same text it had when it lived beside the fader code.
 */
internal object KoCrossfadeLog {
    private const val TAG_LOG = "BitChord"

    fun skipped(tag: String, outgoingId: String?, incomingId: String?, reason: CrossfadeSkipReason) {
        Log.d(tag, "crossfade: skipped $outgoingId->$incomingId reason=${reason.wire}")
        TrackLog.d(
            TAG_LOG,
            "crossfade: skipped $outgoingId->$incomingId reason=${reason.wire}",
            about = incomingId,
        )
    }

    fun arm(tag: String, outId: String?, inId: String?, fadeMs: Long, cueMs: Long, smart: Boolean) {
        TrackLog.d(
            TAG_LOG,
            "crossfade: arm out=$outId in=$inId " +
                "fade=${fadeMs}ms cue=${cueMs}ms smart=$smart",
            about = inId,
        )
    }

    fun plan(
        tag: String,
        currentItem: MediaItem,
        nextItem: MediaItem,
        plan: TransitionPlan,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
    ) {
        Log.d(
            tag,
            "automix: ${currentItem.mediaId}->${nextItem.mediaId} " +
                "beatAligned=${if (plan.beatAligned) "y" else "n"} " +
                "phrase=${if (plan.phraseAligned) "y" else "n"} " +
                "key=${plan.keyRelation.wire} " +
                "keyRaw=${currentAnalysis.key}/${nextAnalysis.key} " +
                "keyConf=${"%.2f".format(Locale.ROOT, currentAnalysis.keyConfidence)}/" +
                "${"%.2f".format(Locale.ROOT, nextAnalysis.keyConfidence)} " +
                "loudness=${"%+.1f".format(Locale.ROOT, plan.loudnessGainDb)}dB " +
                "style=${plan.transitionStyle.name.lowercase(Locale.ROOT)}" +
                (if (plan.transitionStyle == TransitionStyle.DJ_FILTER) {
                    "/${plan.filterVariant.name.lowercase(Locale.ROOT)}"
                } else {
                    ""
                }) +
                " character=${plan.character.wire}",
        )
    }

    fun fadeStart(
        tag: String,
        out: ExoPlayer,
        into: ExoPlayer,
        outItem: MediaItem?,
        inItem: MediaItem?,
        analysisMs: Long,
        standbyLeadMs: Long,
        fadeMs: Long,
        cueMs: Long,
        smart: Boolean,
    ) {
        Log.d(tag, "blend start at cue=${into.currentPosition}ms out=${out.currentPosition}ms")
        Log.d(
            tag,
            "automix: ${outItem?.mediaId}->${inItem?.mediaId} " +
                "analysisMs=$analysisMs standbyLeadMs=$standbyLeadMs",
        )
        Log.d(
            tag,
            "arm ready out=${outItem?.mediaId} in=${inItem?.mediaId} took=${standbyLeadMs}ms",
        )
        TrackLog.d(
            TAG_LOG,
            "crossfade: start out=${outItem?.mediaId} in=${inItem?.mediaId} fade=${fadeMs}ms " +
                "cue=${cueMs}ms smart=$smart " +
                "gains in=${gainText(into.volume)} out=${gainText(out.volume)}",
            about = inItem?.mediaId,
        )
    }

    fun fadeEnd(
        out: ExoPlayer,
        into: ExoPlayer,
        fadeMs: Long,
        fadeStartedAt: Long,
        completedAt: Long,
        echoTailMs: Float,
        progress: Float,
        quarterGains: List<String>,
    ) {
        val quarterText = if (quarterGains.isEmpty()) "" else " ${quarterGains.joinToString(" ")}"
        TrackLog.d(
            TAG_LOG,
            "crossfade: end out=${out.currentMediaItem?.mediaId} in=${into.currentMediaItem?.mediaId} " +
                "fade=${fadeMs}ms overlap=${completedAt - fadeStartedAt}ms tail=${echoTailMs.toLong()}ms " +
                "progress=${gainText(progress)}$quarterText " +
                "gains end=${gainText(into.volume)}/${gainText(out.volume)}",
            about = into.currentMediaItem?.mediaId,
        )
    }

    fun handoff(tag: String, into: ExoPlayer, out: ExoPlayer) {
        Log.d(
            tag,
            "handoff levels inVol=${into.volume} outVol=${out.volume} " +
                "inRate=${into.playbackParameters.speed} outRate=${out.playbackParameters.speed}",
        )
    }

    fun armTimeout(tag: String, elapsedMs: Long, outId: String?, inId: String?, state: Int) {
        Log.d(
            tag,
            "arm timeout after ${elapsedMs}ms out=$outId in=$inId state=$state",
        )
    }

    fun armVolumeRestored(tag: String, volume: Float) {
        Log.w(tag, "arm: outgoing volume was $volume; restoring full level")
    }

    fun abort(tag: String, outId: String?, inId: String?, phase: Any, handedOff: Boolean) {
        TrackLog.d(
            TAG_LOG,
            "crossfade: abort out=$outId in=$inId phase=$phase handedOff=$handedOff",
        )
    }

    fun finish(tag: String, inVol: Float?, outVol: Float?, handedOff: Boolean) {
        Log.d(
            tag,
            "finish levels inVol=$inVol outVol=$outVol handedOff=$handedOff",
        )
    }

    fun gainText(gain: Float): String =
        String.format(Locale.ROOT, "%.3f", gain.coerceIn(0f, 1f))
}
