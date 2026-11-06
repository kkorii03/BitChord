package com.music.bitchord.ko

import com.music.bitchord.playback.contextCycleId
import com.music.bitchord.playback.queueTier
import com.music.bitchord.playback.queueEntryId
import com.music.bitchord.playback.canonicalIndex
import com.music.bitchord.playback.isVideoRow

import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.StreamContainer
import com.music.bitchord.playback.StreamRefusalRecovery
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.UnplayableRecovery
import com.music.bitchord.playback.UnplayableRecoveryAction
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.CrossfadeController
import com.music.bitchord.playback.toSong
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.LoudnessProcessor
import com.music.bitchord.playback.HqSwapPolicy
import com.music.bitchord.playback.handoffKey
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.playback.EXTRA_QUALITY_UPGRADED
import com.music.bitchord.playback.autoplaySupplierId
import com.music.bitchord.playback.autoplayRefillNeedsLoad
import com.music.bitchord.playback.applyPlaybackSpeed
import com.music.bitchord.playback.PlaybackService.BlockedStartCandidate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.music.bitchord.data.TelemetryProvenance
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.music.bitchord.playback.audio.usb.UsbDirectManager
import com.music.bitchord.playback.audio.usb.DirectUsbProbeResult
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.guava.future
import com.music.bitchord.playback.audio.AudioOutputHealth
import com.music.bitchord.playback.audio.AudioSilenceRecovery
import com.music.bitchord.playback.audio.DspChain
import com.music.bitchord.playback.cast.CastController
import com.music.bitchord.playback.cast.CastPlayback
import com.music.bitchord.playback.cast.CastSink
import com.music.bitchord.playback.cast.RemoteSpeaker
import com.music.bitchord.playback.cast.Speakers
import com.music.bitchord.playback.cast.CastStream
import com.music.bitchord.playback.audio.PrecisionAudioSink
import com.music.bitchord.playback.audio.ResamplingAudioProcessor
import com.music.bitchord.playback.audio.SilenceSkipper
import com.music.bitchord.playback.dsd.DsdExtractorsFactory
import com.music.bitchord.playback.audio.DirectAudioProbe
import com.music.bitchord.playback.audio.OutputNegotiator
import com.music.bitchord.playback.audio.PcmEncoding
import com.music.bitchord.playback.audio.SourceDescriptor
import com.music.bitchord.playback.audio.bluetooth.BluetoothAudioTracker
import com.music.bitchord.playback.audio.bluetooth.BluetoothTelemetry
import com.music.bitchord.MainActivity
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.partyQueueIndexOf
import com.music.bitchord.data.listentogether.partyTopUpEchoed
import com.music.bitchord.data.listentogether.partyTopUpLanded
import com.music.bitchord.data.listentogether.partyUpcomingAfter
import com.music.bitchord.data.net.BlockedStartPolicy
import com.music.bitchord.data.net.BlockedStartRescue
import com.music.bitchord.data.net.BlockedStartStep
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.net.UnplayableAlternate
import com.music.bitchord.data.net.HqRetryPolicy
import com.music.bitchord.data.net.sessionScopedCacheHit
import com.music.bitchord.data.net.AUTOPLAY_MIN_QUEUED_AHEAD
import com.music.bitchord.data.net.AutoplayQueueStatus
import com.music.bitchord.data.net.autoplayTopUpNeeded
import com.music.bitchord.data.net.autoplayTopUpSelection
import com.music.bitchord.data.net.autoplayToggleResult
import com.music.bitchord.data.net.repeatModeAutoplayResult
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsOverrideStore
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.model.NOTIFICATION_ART_PX
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.download.DownloadHealth
import com.music.bitchord.download.Downloads
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.music.bitchord.data.Http
import com.music.bitchord.auth.SignedIn
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.DiagnosticsState
import com.music.bitchord.data.TrackDiagnostics
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.net.ResolutionFacts
import com.music.bitchord.data.net.TrackSummaryFormat
import com.music.bitchord.data.discord.DiscordRPC
import com.music.bitchord.data.discord.discordAudioQualityLine
import com.music.bitchord.data.innertube.PlaybackTracker
import com.music.bitchord.data.stats.ListeningRecorder
import com.music.bitchord.data.stats.WriteThrottle
import com.music.bitchord.data.taste.TasteSignals
import com.music.bitchord.data.innertube.PlayerClient
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.innertube.UnplayableReason
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongOrigins
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.model.isUnresolvedSpotify
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.scrobbling.ListenBrainzManager
import com.music.bitchord.data.scrobbling.ScrobbleManager
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.EqualizerMode
import com.music.bitchord.data.settings.ListeningSink
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.data.settings.PrivateListening
import com.music.bitchord.data.sources.QualityUpgradeAdvice
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.PlayingAudioFormat
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.SourceTrackKeys
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.smart.AnalysisWindow
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import com.music.bitchord.playback.smart.HqAudioVerify
import com.music.bitchord.playback.smart.HqVerificationStore
import com.music.bitchord.playback.smart.VersionAudioAligner
import com.music.bitchord.playback.smart.VersionSwapPolicy
import com.music.bitchord.widget.MediaWidget
import com.music.bitchord.widget.MediaWidgetSnapshot
import com.music.bitchord.wear.WearBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.Locale
import java.util.UUID

/**
 * Pulls a verified lossless stream onto disk while its low-quality copy is
 * still playing, under the same rendition key a manual swap would use.
 *
 * This is the fallback for a swap that cannot happen now — too little of
 * the track left, a crossfade in flight, or a download that failed. The
 * completed copy is what the next play of this track opens.
 */
private fun MediaItem.Builder.resolvedStreamType(streamUrl: String): MediaItem.Builder =
    StreamContainer.manifestMimeOf(streamUrl)?.let(::setMimeType) ?: this

internal fun PlaybackService.koPrefetchLosslessForNextPlay(
    mediaId: String,
    uri: Uri?,
    stream: SourceStream,
    title: String?,
) {
    if (uri == null) return
    if (!HqQuality.isLosslessTier(stream.format)) return
    val upgraded = QualityUpgrade.upgradedUri(uri.toString())
    AudioCache.prefetchVerifiedRendition(Uri.parse(upgraded), title, stream)
}

/**
 * The lossless copy of the track that is already playing replaces it as
 * soon as the whole file is on disk, through the spare player and a short
 * equal-power blend.
 *
 * @return true when the session was handed to the replacement.
 */
internal suspend fun PlaybackService.koSwapLosslessMidSong(
    mediaId: String,
    uri: Uri,
    stream: SourceStream,
    title: String?,
    verified: HqVerificationStore.Verified,
): Boolean {
    val beforeDownload = koHqSwapDecision(mediaId, verified)
    if (!beforeDownload.allowed) {
        TrackLog.d(
            "BitChord",
            "hq-swap: $mediaId skipped ${beforeDownload.reason}",
            about = mediaId,
        )
        return false
    }
    // The candidate's offset is measured against the YouTube clock. When a
    // substitute is already playing, its own verified offset is what maps
    // the playhead back; without that answer a seek would land on the wrong
    // audio, so the copy waits for the next play instead.
    if (koCurrentIsSubstitute(mediaId) && koCurrentPlayingVerification(mediaId) == null) {
        TrackLog.d("BitChord", "hq-swap: $mediaId skipped current-clock-unknown", about = mediaId)
        return false
    }
    val upgradedUri = Uri.parse(QualityUpgrade.upgradedUri(uri.toString()))
    val downloaded = AudioCache.downloadVerifiedRendition(upgradedUri, title, stream)
    if (!downloaded) {
        TrackLog.d("BitChord", "hq-swap: $mediaId skipped download-failed", about = mediaId)
        return false
    }
    val live = koHqSwapDecision(mediaId, verified)
    if (!live.allowed) {
        TrackLog.d("BitChord", "hq-swap: $mediaId skipped ${live.reason}", about = mediaId)
        return false
    }
    TrackLog.d(
        "BitChord",
        "hq-swap: $mediaId start at=${live.targetPositionMs} offset=${verified.offsetMs}",
        about = mediaId,
    )
    val currentVerified = koCurrentPlayingVerification(mediaId)
    hqSwapOwnsStandby = true
    markAudioTrackReason("hq-swap")
    val swapped = try {
        koSmoothSwapToLossless(
            mediaId,
            stream,
            verified,
            upgradedUri.toString(),
            currentVerified?.offsetMs ?: 0L,
            currentVerified?.rate ?: 1.0,
        )
    } finally {
        hqSwapOwnsStandby = false
    }
    if (!swapped) {
        TrackLog.d("BitChord", "hq-swap: $mediaId skipped swap-failed", about = mediaId)
        return false
    }
    TrackLog.d("BitChord", "hq-swap: $mediaId done", about = mediaId)
    return true
}

internal fun PlaybackService.koHqSwapDecision(
    mediaId: String,
    verified: HqVerificationStore.Verified,
): HqSwapPolicy.Decision {
    val exoPlayer = player
    val item = exoPlayer?.currentMediaItem
    val current = koCurrentPlayingVerification(mediaId)
    // The same grace the manual swap uses: cutting in the instant a blend
    // lands is heard as the transition having broken, not as a quiet
    // improvement. Only a real transition leaves a timestamp here.
    val justAfterTransition = crossfade?.msSinceTransition()
        ?.let { it < PlaybackService.UPGRADE_AFTER_CROSSFADE_MS } == true
    return HqSwapPolicy.decide(
        positionMs = exoPlayer?.currentPosition ?: 0L,
        durationMs = exoPlayer?.duration ?: 0L,
        crossfadeActive = crossfade?.isTransitioning() == true || justAfterTransition,
        sameTrack = item?.mediaId == mediaId,
        offsetMs = verified.offsetMs,
        rate = verified.rate,
        currentOffsetMs = current?.offsetMs ?: 0L,
        currentRate = current?.rate ?: 1.0,
        pinned = OriginalVersion.isPinned(mediaId),
        versionSwapActive = versionSwapJob?.isActive == true,
        videoRadio = item?.toSong()?.videoRadio == true,
        isVideo = item?.toSong()?.isVideo == true,
    )
}

/** The verified clock of the substitute playing [mediaId], or null for YouTube's own copy. */
internal fun PlaybackService.koCurrentPlayingVerification(mediaId: String): HqVerificationStore.Verified? {
    val snapshot = NerdStats.current.value?.takeIf { it.mediaId == mediaId }
    // The decoder's own source identity wins when it has spoken: it names
    // the stream actually being read, while [StreamChoice] can still hold
    // the candidate an upgrade tried and lost. Reading the choice first
    // mapped the new lossless copy through the wrong offset in exactly
    // that window. A snapshot without a source id has not spoken about the
    // source, so the recorded choice is the fallback rather than a null
    // verdict that makes the current clock look unknown — unless the
    // snapshot already names YouTube, where a stale substitute choice
    // would invent a clock the direct copy does not have.
    val snapshotKind = snapshot?.sourceConfigId?.let { SourceRegistry.config(it)?.kind }
    val choice = if (HqVerificationStore.shouldFallBackToChoice(
            snapshotSourceConfigId = snapshot?.sourceConfigId,
            snapshotKind = snapshotKind,
            snapshotSourceName = snapshot?.sourceName,
        )
    ) {
        StreamChoice.of(mediaId)
    } else {
        null
    }
    val identity = HqVerificationStore.sourceIdentity(
        snapshotSourceConfigId = snapshot?.sourceConfigId,
        snapshotTrackId = snapshot?.sourceTrackId,
        choiceSourceConfigId = choice?.sourceConfigId,
        choiceTrackId = choice?.trackId,
    ) ?: return null
    if (SourceRegistry.config(identity.sourceConfigId)?.kind == SourceKind.YOUTUBE) return null
    return HqVerificationStore.get(
        this,
        mediaId,
        identity.sourceConfigId,
        identity.trackId,
    )
}

/** Whether what is playing came from a source standing in for YouTube. */
internal fun PlaybackService.koCurrentIsSubstitute(mediaId: String): Boolean {
    val snapshot = NerdStats.current.value?.takeIf { it.mediaId == mediaId }
    if (snapshot != null) {
        val kind = snapshot.sourceConfigId?.let { SourceRegistry.config(it)?.kind }
        when {
            kind == SourceKind.YOUTUBE -> return false
            kind != null -> return true
            snapshot.sourceName == "YouTube" -> return false
            snapshot.sourceName != null -> return true
        }
    }
    if (StreamChoice.isSubstitute(mediaId)) return true
    val choice = StreamChoice.of(mediaId) ?: return false
    val sourceId = choice.sourceConfigId ?: return false
    return SourceRegistry.config(sourceId)?.kind != SourceKind.YOUTUBE
}

/**
 * Opens the verified lossless copy on the spare player at the position the
 * current copy is at, then blends the session across in [HqSwapPolicy.FADE_MS].
 *
 * The replacement is a fresh player opening a fresh rendition — never the
 * old stream continued at the same byte offset, which would splice two
 * containers together. The item keeps the same media id and queue identity,
 * so lyrics, scrobbling and the Automix analysis stay attached to the same
 * recording; only the audio source changes.
 */
internal suspend fun PlaybackService.koSmoothSwapToLossless(
    mediaId: String,
    stream: SourceStream,
    verified: HqVerificationStore.Verified,
    upgradedUri: String,
    currentOffsetMs: Long,
    currentRate: Double,
): Boolean = withContext(Dispatchers.Main) {
    val activePlayer = player ?: return@withContext false
    val index = activePlayer.currentMediaItemIndex
    if (index !in 0 until activePlayer.mediaItemCount) return@withContext false
    val currentItem = activePlayer.currentMediaItem ?: return@withContext false
    if (currentItem.mediaId != mediaId) return@withContext false
    if (crossfade?.isTransitioning() == true) return@withContext false
    val standbyPlayer = spare ?: return@withContext false

    fun releaseStandby() {
        standbyPlayer.stop()
        standbyPlayer.clearMediaItems()
        standbyPlayer.volume = 1f
        activePlayer.volume = 1f
        if (swappingMediaId == mediaId) swappingMediaId = null
    }

    // The caller only logs "swap-failed", so each gate names itself here.
    fun refuse(reason: String): Boolean {
        TrackLog.d("BitChord", "hq-swap: $mediaId aborted $reason", about = mediaId)
        releaseStandby()
        return false
    }

    fun unfitReason(): String? = HqSwapPolicy.standbyUnfitReason(
        expectedMediaId = mediaId,
        expectedIndex = index,
        standbyMediaId = standbyPlayer.currentMediaItem?.mediaId,
        standbyIndex = standbyPlayer.currentMediaItemIndex,
        standbyState = standbyPlayer.playbackState,
        standbyPositionMs = standbyPlayer.currentPosition,
        standbyDurationMs = standbyPlayer.duration,
        suppressed = activePlayer.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    )

    val sourceName = stream.sourceConfigId
        ?.let { SourceRegistry.config(it)?.displayName }
        ?: "Upgrade"
    val upgradedMetadata = currentItem.mediaMetadata.buildUpon()
        .setExtras(Bundle(currentItem.mediaMetadata.extras ?: Bundle()).apply {
            putBoolean(EXTRA_QUALITY_UPGRADED, true)
        })
        .build()
    val upgradedItem = currentItem.buildUpon()
        .setUri(upgradedUri)
        .setMediaMetadata(upgradedMetadata)
        .resolvedStreamType(stream.url)
        .build()
    val newItems = (0 until activePlayer.mediaItemCount).map { i ->
        if (i == index) upgradedItem else activePlayer.getMediaItemAt(i)
    }
    val wasPlaying = activePlayer.playWhenReady
    val startAt = HqSwapPolicy.targetPositionMs(
        HqSwapPolicy.toYoutubeMs(activePlayer.currentPosition, currentOffsetMs, currentRate),
        verified.offsetMs,
        verified.rate,
    )
    // What the replacement has to prove itself against, captured before the
    // swap overwrites the claims for this track. The candidate's own length
    // is the old track's YouTube length carried through its verified
    // offset and rate, not the outgoing copy's raw duration: a different
    // intro would otherwise look like a short file and be reverted.
    val previousUri = currentItem.localConfiguration?.uri?.toString()
    val previousDuration = activePlayer.duration
    val previousFormat = NerdStats.declaredFormat(mediaId)
    val expectedDuration = if (previousDuration > 0L) {
        HqSwapPolicy.targetPositionMs(
            HqSwapPolicy.toYoutubeMs(previousDuration, currentOffsetMs, currentRate),
            verified.offsetMs,
            verified.rate,
        )
    } else {
        previousDuration
    }
    // The standby is the session's player only once this swap adopts it;
    // every other ending owes it a release, cancellation included.
    var adopted = false
    try {
        standbyPlayer.stop()
        standbyPlayer.clearMediaItems()
        standbyPlayer.skipSilenceEnabled = activePlayer.skipSilenceEnabled
        standbyPlayer.repeatMode = activePlayer.repeatMode
        standbyPlayer.shuffleModeEnabled = activePlayer.shuffleModeEnabled
        standbyPlayer.applyPlaybackSpeed(AppSettings.playbackSpeed.value)
        standbyPlayer.volume = 0f
        standbyPlayer.setMediaItems(newItems, index, startAt)
        spareLoudness().track(mediaId, nextMediaIdOf(standbyPlayer))
        standbyPlayer.playWhenReady = false
        standbyPlayer.prepare()

        // Readiness is an event, not a clock: the standby tells the listener
        // when it is ready, has started, or has failed. Polling these two
        // properties every 10–20ms for seven seconds was pure battery cost.
        val ready = koAwaitPlayerCondition(
            HqSwapPolicy.PREPARE_TIMEOUT_MS,
            activePlayer,
            standbyPlayer,
        ) {
            player !== activePlayer ||
                activePlayer.currentMediaItemIndex != index ||
                activePlayer.currentMediaItem?.mediaId != mediaId ||
                standbyPlayer.playbackState == Player.STATE_READY ||
                (standbyPlayer.playbackState == Player.STATE_IDLE &&
                    standbyPlayer.playerError != null)
        }
        if (!ready || !koIsStillSwapping(activePlayer, index, mediaId) ||
            standbyPlayer.playbackState != Player.STATE_READY
        ) {
            releaseStandby()
            return@withContext false
        }
        unfitReason()?.let { return@withContext refuse(it) }

        if (wasPlaying) {
            standbyPlayer.seekTo(
                index,
                HqSwapPolicy.targetPositionMs(
                    HqSwapPolicy.toYoutubeMs(activePlayer.currentPosition, currentOffsetMs, currentRate),
                    verified.offsetMs,
                    verified.rate,
                ),
            )
            standbyPlayer.play()
            val started = koAwaitPlayerCondition(
                HqSwapPolicy.START_TIMEOUT_MS,
                activePlayer,
                standbyPlayer,
            ) {
                !koIsStillSwapping(activePlayer, index, mediaId) ||
                    standbyPlayer.isPlaying ||
                    standbyPlayer.playbackState == Player.STATE_ENDED ||
                    (standbyPlayer.playbackState == Player.STATE_IDLE &&
                        standbyPlayer.playerError != null)
            }
            if (started && koIsStillSwapping(activePlayer, index, mediaId) &&
                standbyPlayer.isPlaying
            ) {
                // Asked before the seek, which would put a standby that has
                // run on to the next row back on this one and hide that.
                unfitReason()?.let { return@withContext refuse(it) }
                standbyPlayer.seekTo(
                    index,
                    HqSwapPolicy.targetPositionMs(
                        HqSwapPolicy.toYoutubeMs(activePlayer.currentPosition, currentOffsetMs, currentRate),
                        verified.offsetMs,
                        verified.rate,
                    ),
                )
            } else {
                releaseStandby()
                return@withContext false
            }
        } else {
            val seeked = koAwaitPlayerCondition(
                HqSwapPolicy.START_TIMEOUT_MS,
                activePlayer,
                standbyPlayer,
            ) {
                !koIsStillSwapping(activePlayer, index, mediaId) ||
                    standbyPlayer.playbackState == Player.STATE_READY ||
                    standbyPlayer.playbackState == Player.STATE_ENDED ||
                    (standbyPlayer.playbackState == Player.STATE_IDLE &&
                        standbyPlayer.playerError != null)
            }
            if (!seeked || !koIsStillSwapping(activePlayer, index, mediaId)) {
                releaseStandby()
                return@withContext false
            }
        }

        swappingMediaId = mediaId
        swapCutAt = SystemClock.elapsedRealtime()

        val fadeStart = SystemClock.elapsedRealtime()
        hqSwappingMediaId = mediaId
        while (isActive) {
            if (player !== activePlayer ||
                activePlayer.currentMediaItemIndex != index ||
                activePlayer.currentMediaItem?.mediaId != mediaId
            ) {
                releaseStandby()
                return@withContext false
            }
            // The first pass also checks the seek just made, and the last pass
            // is what adoption relies on: nothing suspends between it and the
            // handover.
            unfitReason()?.let { return@withContext refuse(it) }
            if (standbyPlayer.playWhenReady != activePlayer.playWhenReady) {
                standbyPlayer.playWhenReady = activePlayer.playWhenReady
            }
            val progress = ((SystemClock.elapsedRealtime() - fadeStart).toFloat() /
                HqSwapPolicy.FADE_MS).coerceIn(0f, 1f)
            val inGain = sin(progress * (PI / 2.0)).toFloat()
            val outGain = cos(progress * (PI / 2.0)).toFloat()
            standbyPlayer.volume = inGain
            activePlayer.volume = outGain
            val trim = 1f / sqrt((inGain + outGain).coerceAtLeast(1f))
            loudnessA.setBlendTrim(trim)
            loudnessB.setBlendTrim(trim)
            if (progress >= 1f) break
            delay(HqSwapPolicy.FADE_STEP_MS)
        }
        if (!isActive) {
            releaseStandby()
            return@withContext false
        }
        // The pin and the item are read again here, not only before the
        // prepare: a revert can land during the prepare or the blend, and
        // it leaves the media id, index and player unchanged. Without this
        // the lossless player would still be adopted over the listener's
        // choice.
        if (!HqSwapPolicy.stillOwnsItem(
                pinned = OriginalVersion.isPinned(mediaId),
                currentUri = activePlayer.currentMediaItem?.localConfiguration?.uri?.toString(),
                expectedUri = previousUri,
            )
        ) {
            releaseStandby()
            return@withContext false
        }
        // The session player has owned the queue and its modes since the copy
        // was taken, so the standby catches up here, the way a crossfade does
        // at its handoff. The current row stays out of it, so nothing is cut.
        standbyPlayer.repeatMode = activePlayer.repeatMode
        standbyPlayer.shuffleModeEnabled = activePlayer.shuffleModeEnabled
        val queueEdits = HqSwapPolicy.queueEdits(
            activeKeys = (0 until activePlayer.mediaItemCount).map { activePlayer.getMediaItemAt(it).swapQueueKey() },
            standbyKeys = (0 until standbyPlayer.mediaItemCount).map { standbyPlayer.getMediaItemAt(it).swapQueueKey() },
            currentIndex = index,
        )
        if (queueEdits.tail) {
            standbyPlayer.replaceMediaItems(
                index + 1,
                standbyPlayer.mediaItemCount,
                (index + 1 until activePlayer.mediaItemCount).map { activePlayer.getMediaItemAt(it) },
            )
        }
        if (queueEdits.head) {
            standbyPlayer.replaceMediaItems(0, index, (0 until index).map { activePlayer.getMediaItemAt(it) })
        }
        // Recorded only once the swap is certain: every abort above leaves
        // the YouTube stream playing, and the badge, the stream choice and
        // the upgrade state must keep naming that stream, not a lossless
        // copy that never became audible.
        NerdStats.onSourceStream(mediaId, stream.format, sourceName)
        NerdStats.recordSource(mediaId, sourceName)
        StreamChoice.remember(mediaId, stream, substituted = stream.sourceConfigId != null)
        QualityUpgrade.unshelve(mediaId)
        val revertPosition = activePlayer.currentPosition.coerceAtLeast(0L)
        adoptPlayerForVersionSwap(outgoing = activePlayer, incoming = standbyPlayer)
        adopted = true
        // The standby's decoder reported its format before the listener
        // was attached, so [onTrackBecameCurrent] clearing the old
        // snapshot would otherwise leave the badge blank until the next
        // format event. Publish the new player's measured format now, so
        // the badge and the manual-upgrade verdict change with the audio.
        publishNerdStats()
        // A replacement that is complete but the wrong length does not
        // raise an error: it plays to its end and ExoPlayer advances the
        // queue. The audition path has [watchUpgrade] for this; the
        // mid-song swap has to give the new stream the same proof or a
        // short file costs the listener the song.
        val dropOldCopy = {
            AudioCache.dropLowerQualityAfterLosslessSwap(
                mediaId,
                AudioCache.cacheKeyOf(Uri.parse(upgradedUri)),
            )
        }
        if (previousUri == null) {
            // Nothing to revert to, so there is nothing the proof could
            // protect; the old copy goes as soon as the swap is adopted.
            dropOldCopy()
        } else {
            watchUpgrade(
                mediaId = mediaId,
                previousUri = previousUri,
                upgradedUri = upgradedUri,
                position = revertPosition,
                previousDuration = previousDuration,
                previousFormat = previousFormat,
                expectedDuration = expectedDuration,
                onProven = dropOldCopy,
            )
        }
        true
    } finally {
        hqSwappingMediaId = null
        loudnessA.setBlendTrim(1f)
        loudnessB.setBlendTrim(1f)
        // A swap that never reached the adoption still owns the standby it
        // armed: left running it keeps the old song decoding on mute, and a
        // cancellation mid-blend leaves the outgoing player at the gain the
        // fade had already taken off it. [releaseStandby] undoes both.
        if (!adopted) releaseStandby()
    }
}

/**
 * A queue row's identity for catching edits made during a swap. The URI is part
 * of it because a row can be replaced in place under the same entry id.
 */
private fun MediaItem.swapQueueKey(): String =
    "${handoffKey()}|$mediaId|${localConfiguration?.uri}"

/** Whether the session still belongs to the track and player a swap started on. */
internal fun PlaybackService.koIsStillSwapping(
    activePlayer: ExoPlayer,
    index: Int,
    mediaId: String,
): Boolean =
    player === activePlayer &&
        activePlayer.currentMediaItemIndex == index &&
        activePlayer.currentMediaItem?.mediaId == mediaId

/**
 * Suspends until [condition] holds or [timeoutMs] passes, driven by the
 * players' own callbacks rather than a polling loop.
 *
 * The mid-song swap used to ask `playbackState` and `isPlaying` every
 * 10–20ms for up to seven seconds, which is pure battery cost for an
 * answer the player already broadcasts. The initial check and the check
 * after registering close the window between them, so an event that
 * lands while the listener is being attached cannot be missed.
 */
internal suspend fun PlaybackService.koAwaitPlayerCondition(
    timeoutMs: Long,
    vararg players: Player,
    condition: () -> Boolean,
): Boolean {
    if (condition()) return true
    val reached = CompletableDeferred<Boolean>()
    val listener = object : Player.Listener {
        fun check() {
            if (!reached.isCompleted && condition()) reached.complete(true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) = check()
        override fun onIsPlayingChanged(isPlaying: Boolean) = check()
        override fun onPlayerError(error: PlaybackException) = check()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = check()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = check()
    }
    players.forEach { it.addListener(listener) }
    if (condition()) reached.complete(true)
    return try {
        withTimeoutOrNull(timeoutMs) { reached.await() } == true
    } finally {
        players.forEach { it.removeListener(listener) }
    }
}

/**
 * The queue item for [item]'s verified lossless copy, or null when there is
 * nothing better to prepare. Called when the standby arms and again when a
 * verification lands during ARMING.
 */
internal fun MediaItem.koLosslessAheadItem(): MediaItem? {
    val id = mediaId
    val choice = StreamChoice.of(id) ?: return null
    val sourceId = choice.sourceConfigId ?: return null
    if (SourceRegistry.config(sourceId)?.kind == SourceKind.YOUTUBE) return null
    if (!HqQuality.isLosslessTier(choice.format)) return null
    val uri = localConfiguration?.uri ?: return null
    if (uri.getQueryParameter(QualityUpgrade.MARKER) != null) return null
    QualityUpgrade.force(id, choice)
    return buildUpon()
        .setUri(QualityUpgrade.upgradedUri(uri.toString()))
        .resolvedStreamType(choice.url)
        .build()
}

internal fun PlaybackService.koLogHqAhead(mediaId: String) {
    TrackLog.d("BitChord", "hq-ahead: $mediaId re-prepared next player as FLAC", about = mediaId)
}

/**
 * A verification landing while the next transition is already armed means
 * the standby was buffering the lossy copy. Reload it onto the verified
 * lossless rendition before the blend starts; after that point the listener
 * is hearing it and the swap is no longer free.
 */
internal fun PlaybackService.koRefreshArmedIncomingLossless() {
    val service = this
    val exoPlayer = player ?: return
    val nextIndex = exoPlayer.nextMediaItemIndex
    if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until exoPlayer.mediaItemCount) return
    val controller = crossfade ?: return
    if (!controller.isArming()) return
    val next = exoPlayer.getMediaItemAt(nextIndex)
    val prepared = next.koLosslessAheadItem() ?: return
    if (controller.rearmIncoming(prepared)) koLogHqAhead(next.mediaId)
}
