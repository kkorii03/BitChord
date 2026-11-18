package com.music.bitchord.ko

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.playback.DirectSourceGate
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * nextprep fix 3: a Direct Source Stream proof that lands after the next row
 * was settled on YouTube, but before it sounds, goes to the standby.
 *
 * vc213 log: the window's lookup outran its LOOKUP_TIMEOUT_MS, the row was
 * pinned to YouTube ([DirectSourceGate.startedOnYouTube]) and the lookup ran on
 * into [QualityUpgrade]'s second look. When its proof then landed seconds before
 * the blend, nothing looked at it until the song was already audible, so the
 * song started on YouTube and was swapped mid-song.
 *
 * The proof is held here, not pinned: pinning a FLAC over the row's partial
 * YouTube cache entry would let the next plain-key read append FLAC bytes to
 * Opus. The standby takes it ([take]) when it arms, or re-arms on [landed] while
 * arming, onto the upgrade rendition's own cache key; only then is it pinned
 * ([adopted]). Event-driven only: the lookup's own completion, no timer.
 */
internal object KoLateDirectProof {

    @Volatile
    private var held: Pair<String, SourceStream>? = null

    private val _landed = MutableStateFlow(0L)

    /** Bumped when a late proof is held; PlaybackService re-runs the armed standby's refresh on it. */
    val landed: StateFlow<Long> = _landed.asStateFlow()

    /** [videoId] started on YouTube with [running] still looking: hold its answer if it lands before [audible]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watch(videoId: String, running: Deferred<SourceStream?>?, audible: () -> Boolean) {
        val lookup = running ?: return
        lookup.invokeOnCompletion { cause ->
            // A completion handler must not throw: the late proof is a bonus, the mid-song swap still holds it.
            if (cause == null) runCatching { offer(videoId, lookup.getCompleted(), audible()) }
        }
    }

    /** Whether [stream] is worth the standby: a lossless copy from a source other than YouTube. */
    internal fun worthStandby(stream: SourceStream): Boolean =
        stream.sourceConfigId?.let { SourceRegistry.config(it)?.kind != SourceKind.YOUTUBE } == true &&
            HqQuality.isLosslessTier(stream.format)

    internal fun offer(videoId: String, stream: SourceStream?, audible: Boolean): Boolean {
        val late = stream ?: return false
        // Audible already: the second look's mid-song swap takes it, as before.
        if (audible || !worthStandby(late)) return false
        held = videoId to late
        TrackLog.d(
            TAG,
            "direct: late proof for $videoId (${late.format.summary}) landed before it sounds; offering it to the standby",
            about = videoId,
        )
        _landed.update { it + 1L }
        return true
    }

    /** The held proof for [videoId], once, while it is still not audible and nothing better is pinned. */
    fun take(videoId: String): SourceStream? {
        val (id, stream) = held ?: return null
        if (id != videoId) return null
        held = null
        if (DirectSourceGate.isPlaying(videoId) || StreamChoice.isSubstitute(videoId) ||
            StreamChoice.substitutesRefused(videoId) || videoId in QualityUpgrade.refused
        ) {
            return null
        }
        return stream
    }

    /** DirectSourceGate's drop: only the row still next and not audible keeps its held proof. */
    fun keepOnly(next: String?) {
        val (id, _) = held ?: return
        if (id != next) held = null
    }

    /** The standby opened [videoId] on [stream]: pin it and close the second look the late lookup was handed to. */
    fun adopted(videoId: String, stream: SourceStream) {
        StreamChoice.remember(videoId, stream, substituted = true)
        QualityUpgrade.pending.remove(videoId)
        NerdStats.onLosslessRaceEnd(videoId)
        TrackLog.d(TAG, "next-row re-armed as FLAC after late proof: $videoId ${stream.format.summary}", about = videoId)
    }

    /** Test hook. */
    internal fun reset() {
        held = null
    }

    private const val TAG = "BitChord"
}
