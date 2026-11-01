package com.music.bitchord.ko

import android.media.AudioDeviceInfo
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.PlaybackTracker
import com.music.bitchord.data.stats.ListeningRecorder
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongOrigins
import com.music.bitchord.auth.SignedIn
import com.music.bitchord.playback.AudioOutputPolicy
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.toSong
import com.music.bitchord.playback.SessionFavoriteState
import com.music.bitchord.widget.MediaWidgetSnapshot
import kotlin.math.abs
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** How long an AudioTrack-reason hint stays valid for the next event. */
internal const val AUDIO_TRACK_REASON_TTL_MS = 10_000L

/**
 * How often the playhead is written back while playing. The restore point only
 * has to be roughly where the listener was; pause, track change and service
 * stop write it exactly.
 */
internal const val PLAYBACK_STATE_SAVE_MS = 30_000L

/** AutoPlay's refill marker and its generation, kept outside the upstream class body. */
internal class KoAutoplayAdvanceState {
    var pendingFor: String? = null
    var pendingTarget: String? = null
    var refillGeneration: Int = 0
}

private object KoAutoplayAdvanceStates {
    private val states = java.util.WeakHashMap<PlaybackService, KoAutoplayAdvanceState>()

    fun of(service: PlaybackService): KoAutoplayAdvanceState = synchronized(states) {
        states.getOrPut(service) { KoAutoplayAdvanceState() }
    }
}

internal val PlaybackService.koAutoplayAdvance: KoAutoplayAdvanceState
    get() = KoAutoplayAdvanceStates.of(this)

/** Settle window that keeps our own SponsorBlock jump from counting as a manual seek. */
private const val KO_OWN_SKIP_SETTLE_MS = 1_000L

/** Notes a seek that the listener made, ignoring the jump out of a skip segment. */
internal fun PlaybackService.koNoteManualSeek(positionMs: Long) {
    val now = SystemClock.elapsedRealtime()
    val ours = skipSeekTargetMs >= 0L &&
        now - skipSeekAtMs < KO_OWN_SKIP_SETTLE_MS &&
        abs(positionMs - skipSeekTargetMs) < KO_OWN_SKIP_SETTLE_MS
    if (ours) {
        skipSeekTargetMs = -1L
    } else {
        lastManualSeekAtMs = now
        lastManualSeekPositionMs = positionMs
    }
}

/** Whether Like is offered for [item] — the player's own rule for every surface. */
internal fun koCanLike(service: PlaybackService, item: MediaItem?): Boolean =
    item != null && SignedIn.state(service).value && !SongOrigins.isLocalFile(item.toSong())

/** The rating the notification star and widget draw, with the persisted fallback. */
internal fun PlaybackService.koSessionFavoriteStatus(videoId: String?): LikeStatus {
    val override = videoId?.let { LikeState.overrides.value[it] }
    val persisted = if (videoId != null && override == null) {
        MediaWidgetSnapshot.load(this)
    } else {
        null
    }
    return SessionFavoriteState.status(videoId, override, persisted)
}

internal fun koLogVersionSwapRefused(sourceSong: Song, targetSong: Song, confidence: Double?) {
    TrackLog.w(
        "BitChord",
        "version swap refused: ${sourceSong.title} -> ${targetSong.title} conf=${confidence ?: -1.0}",
        sourceSong.videoId,
    )
}

internal fun koRotateExpiredHistory(exoPlayer: ExoPlayer, expiredHistory: Int) {
    repeat(expiredHistory) {
        exoPlayer.moveMediaItem(0, exoPlayer.mediaItemCount - 1)
    }
    TrackLog.d(
        "BitChord",
        "queue: move n=$expiredHistory at=0 to=${exoPlayer.mediaItemCount - 1} " +
            "size=${exoPlayer.mediaItemCount} by=history",
    )
}

internal fun PlaybackService.koLogAutoplayEmptyLater(videoId: String, at: Int, size: Int) {
    TrackLog.d(
        "BitChord",
        "autoplay: empty seed=$videoId at=$at size=$size retry=later",
        about = videoId,
    )
}

/** False when this empty answer must end the refill; true when another try is due. */
internal fun PlaybackService.koAutoplayRetryAllowed(
    videoId: String,
    at: Int,
    size: Int,
    generation: Int,
    retriesRemaining: Int,
): Boolean {
    if (at < 0 || at != size - 1) {
        koLogAutoplayEmptyLater(videoId, at, size)
        return false
    }
    if (retriesRemaining <= 0) {
        koReleaseExhaustedAutoplaySeed(videoId, generation, retriesRemaining)
        return false
    }
    return true
}

internal fun PlaybackService.koReleaseExhaustedAutoplaySeed(
    videoId: String,
    generation: Int,
    retriesRemaining: Int,
) {
    if (generation == koAutoplayAdvance.refillGeneration) {
        autoplaySeed = autoplaySeedAfterExhaustedRetries(
            seed = autoplaySeed,
            currentVideoId = videoId,
            retriesRemaining = retriesRemaining,
        )
    }
    TrackLog.d(
        "BitChord",
        "autoplay: empty seed=$videoId retry=exhausted",
        about = videoId,
    )
}

internal fun koDeferManualAnswer(
    startedManual: Boolean,
    manualPending: Boolean,
    manualJoined: Boolean,
): Boolean = QualityUpgrade.defersAutomaticAnswerToManual(
    startedManual = startedManual,
    manualPending = manualPending,
    manualJoined = manualJoined,
)

/** The rate the resampler should open AudioTrack at, or null to keep the decoder's rate. */
internal fun PlaybackService.koResampleTargetRate(inputRateHz: Int): Int? {
    val activeDevice = resolveActiveOutputDevice()
    val routeKind = resolveActiveRouteKind(activeDevice)
    return AudioOutputPolicy.outputSampleRateFor(
        sourceRateHz = inputRateHz,
        routeKind = routeKind,
        advertisedSampleRates = activeDevice?.sampleRates?.toList().orEmpty(),
    )
}

/** Playback preferences that reconcile the current track instead of waiting for the next one. */
internal fun PlaybackService.koObservePlaybackPreferences() {
    scope.launch {
        AppSettings.skipNonMusicParts.drop(1).collect {
            refreshSkipSegmentsForSettings()
        }
    }
    scope.launch {
        AppSettings.skipSponsorParts.drop(1).collect {
            refreshSkipSegmentsForSettings()
        }
    }
    scope.launch {
        AppSettings.privateListening.drop(1).collect { enabled ->
            PlaybackTracker.suppressCurrent(player?.currentMediaItem?.mediaId)
            if (enabled) {
                ListeningRecorder.onPrivateModeEnabled()
                scrobbleManager?.onPrivateModeEnabled()
                listenBrainzSong = null
                listenBrainzStartMs = 0L
                listenBrainzDurationMs = null
                clearDiscordPresence()
            } else {
                player?.takeIf { it.isPlaying }?.let { pushDiscordPresence(it) }
            }
        }
    }
    scope.launch {
        AppSettings.preferMusicOnly.drop(1).collect {
            cancelPrefetch()
            player?.takeIf { it.isPlaying }?.let { prefetchAround(it) }
        }
    }
}

/** The short reason names a diagnostic log reads without the Media3 constants. */
internal fun transitionReason(reason: Int): String = when (reason) {
    Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
    Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
    else -> "OTHER($reason)"
}

internal fun playReason(reason: Int): String = when (reason) {
    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "ui"
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "focus"
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "noisy"
    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end"
    Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressed"
    else -> "other($reason)"
}

/** Which pieces a queue item is missing, for the `play:` line's `meta=`. */
internal fun missingMeta(song: Song?): String {
    if (song == null) return "title,artist,artwork"
    val missing = buildList {
        if (song.title.isBlank()) add("title")
        if (song.artist.isBlank()) add("artist")
        if (song.thumbnailUrl.isNullOrBlank()) add("artwork")
    }
    return missing.ifEmpty { listOf("ok") }.joinToString(",")
}

/**
 * How many entries [PlaybackService.cacheSong] has to drop so that adding one
 * more keeps the cache at or below [max].
 *
 * Pure so the ceiling can be checked without a running service; returns zero
 * while there is still room.
 */
internal fun songCacheEvictionCount(size: Int, max: Int): Int =
    if (size >= max) size - max + 1 else 0

/**
 * Whether the row that just became current is already producing sound.
 *
 * A crossfade says so directly. A gapless auto advance does not: Media3 keeps
 * the player READY across the item boundary, so [Player.Listener.onIsPlayingChanged]
 * never fires and [PlaybackService.audibleMediaId] would still name the
 * outgoing row. Requiring an ended track and a playing player is what separates
 * that continuous handoff from a skip into a row that is still buffering
 * (isPlaying can be true for one event while the incoming row has not sounded).
 */
internal fun rowAlreadySounding(
    alreadyAudible: Boolean,
    previousEnded: Boolean,
    isPlaying: Boolean,
): Boolean = alreadyAudible || (previousEnded && isPlaying)

/**
 * Whether that row is still audible once the song-version decision has run.
 *
 * A gapless advance into a video row is paused by [SongVersionController]'s
 * first-sound hold, so the transition's playing state no longer describes it;
 * treating it as audible there would write firstAudio=0 and start read-ahead
 * for a row nobody can hear yet.
 */
internal fun rowSoundingAfterVersionHold(
    alreadySounding: Boolean,
    versionHeld: Boolean,
): Boolean = alreadySounding && !versionHeld
