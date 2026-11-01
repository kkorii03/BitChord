package com.music.bitchord.ko

import com.music.bitchord.playback.SkipSegments
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.toSong
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.StreamChoice

import com.music.bitchord.ko.koTryAutoplayRefillBeforeStopping
import com.music.bitchord.ko.koSwitchToAlternateOrSkip
import com.music.bitchord.ko.koVerifyBlockedStartCandidate
import com.music.bitchord.ko.koWithAlternateRecording
import com.music.bitchord.ko.koReportPlaybackFailure
import com.music.bitchord.ko.koIsBotChallenge
import com.music.bitchord.ko.koPrefetchLosslessForNextPlay
import com.music.bitchord.ko.koSwapLosslessMidSong
import com.music.bitchord.ko.koHqSwapDecision
import com.music.bitchord.ko.koCurrentPlayingVerification
import com.music.bitchord.ko.koCurrentIsSubstitute
import com.music.bitchord.ko.koSmoothSwapToLossless
import com.music.bitchord.ko.koIsStillSwapping
import com.music.bitchord.ko.koAwaitPlayerCondition
import com.music.bitchord.ko.koLosslessAheadItem
import com.music.bitchord.ko.koLogHqAhead
import com.music.bitchord.ko.koRefreshArmedIncomingLossless
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
import com.music.bitchord.playback.TranslationAhead
import com.music.bitchord.playback.TranslationAheadPolicy
import com.music.bitchord.playback.NextPrepPolicy
import com.music.bitchord.playback.fromAutoplay
import com.music.bitchord.playback.isYouTubeVideoId
import com.music.bitchord.playback.skipCategories
import com.music.bitchord.playback.isTooMuchOfTheVideo
import com.music.bitchord.playback.decideSkip
import com.music.bitchord.playback.SkipAction


/**
 * Asks SponsorBlock about whatever just became current, and forgets the
 * previous track's answer. Off the main thread and never in front of
 * playback: the track is already running by the time anything lands.
 */
internal fun PlaybackService.koLoadSkipSegmentsForCurrentTrack() {
    skipSegmentsJob?.cancel()
    skipSegmentsJob = null
    koCancelSkipSegmentMessages()
    skipSegments = emptyList()
    skipSegmentsVideoId = null
    skippedSegmentIds.clear()
    lastManualSeekAtMs = 0L
    lastManualSeekPositionMs = 0L
    skipSeekTargetMs = -1L
    val exoPlayer = player ?: return
    val videoId = exoPlayer.currentMediaItem?.mediaId ?: return
    if (!AppSettings.skipNonMusicParts.value) return
    if (!isYouTubeVideoId(videoId)) return
    koFetchSkipSegments(videoId)
}

internal fun PlaybackService.koFetchSkipSegments(videoId: String) {
    // Claimed before the request goes out, so nothing asks again while it is
    // in flight — and does not retry a failed lookup for the same track
    // either. The next track is a fresh ask.
    skipSegmentsVideoId = videoId
    skipSegmentsJob = scope.launch(Dispatchers.IO) {
        val found = SkipSegments.segmentsFor(videoId)
        withContext(Dispatchers.Main) {
            if (player?.currentMediaItem?.mediaId != videoId) return@withContext
            skipSegments = found
            koCheckSkipSegments()
            koScheduleSkipSegmentMessages()
        }
    }
}

internal fun PlaybackService.koScheduleSkipSegmentMessages() {
    koCancelSkipSegmentMessages()
    val exoPlayer = player ?: return
    if (!AppSettings.skipNonMusicParts.value) return
    val videoId = exoPlayer.currentMediaItem?.mediaId ?: return
    if (!isYouTubeVideoId(videoId)) return
    if (skipSegmentsVideoId != videoId) return
    if (skipSegments.isEmpty()) return
    val positionMs = exoPlayer.currentPosition
    val durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
    val categories = skipCategories(AppSettings.skipSponsorParts.value)
    val mediaItemIndex = exoPlayer.currentMediaItemIndex
    skipSegments
        .asSequence()
        .filter { it.category in categories && it.id !in skippedSegmentIds }
        .filter { it.startMs > positionMs }
        .filter { !isTooMuchOfTheVideo(it, durationMs) }
        .forEach { segment ->
            val message = exoPlayer.createMessage { _, _ -> koCheckSkipSegments() }
                .setPosition(mediaItemIndex, segment.startMs)
                .setLooper(Looper.getMainLooper())
                .setDeleteAfterDelivery(true)
                .send()
            skipSegmentMessages += message
        }
}

internal fun PlaybackService.koCancelSkipSegmentMessages() {
    skipSegmentMessages.forEach { runCatching { it.cancel() } }
    skipSegmentMessages.clear()
}

/**
 * Applies a SponsorBlock switch to the track already playing.
 *
 * Off cancels the pending messages and forgets the lookup, so nothing can
 * jump the playhead after the listener turned the feature off. On reuses
 * the segments already held for this track (the sponsor-only switch only
 * changes which categories are eligible) and otherwise asks once. It is
 * called from the settings flows, never from a timer.
 */
internal fun PlaybackService.koRefreshSkipSegmentsForSettings() {
    if (!AppSettings.skipNonMusicParts.value) {
        koCancelSkipSegmentMessages()
        skipSegments = emptyList()
        skipSegmentsVideoId = null
        return
    }
    val videoId = player?.currentMediaItem?.mediaId ?: return
    if (videoId == skipSegmentsVideoId && skipSegments.isNotEmpty()) {
        // Same track, already answered: only the category filter moved.
        koScheduleSkipSegmentMessages()
    } else {
        // Nothing held for this track yet (or it was switched on after the
        // track started). [checkSkipSegments] fetches when it is safe to.
        koCheckSkipSegments()
    }
}

/**
 * Jumps the playhead out of a SponsorBlock segment when its message fires.
 *
 * This is an ordinary seek: lyrics follow the playhead and no lyric offset is
 * touched — [com.music.bitchord.playback.PlaybackPosition.seeks] already tells
 * the lyric clock a jump happened.
 */
internal fun PlaybackService.koCheckSkipSegments() {
    val exoPlayer = player ?: return
    if (!exoPlayer.isPlaying) return
    if (!AppSettings.skipNonMusicParts.value) return
    // A party's playhead belongs to whoever is hosting it; a local jump
    // would only be corrected back out on the next reconcile.
    if (ListenTogether.state.value.controlsLocked) return
    val videoId = exoPlayer.currentMediaItem?.mediaId ?: return
    if (!isYouTubeVideoId(videoId)) return
    if (skipSegmentsVideoId != videoId) {
        // Switched on after this track started: ask now, or it would never
        // be asked for this one.
        if (skipSegmentsJob?.isActive != true) koFetchSkipSegments(videoId)
        return
    }
    if (skipSegments.isEmpty()) return
    val now = SystemClock.elapsedRealtime()
    val action = decideSkip(
        segments = skipSegments,
        positionMs = exoPlayer.currentPosition,
        durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L,
        categories = skipCategories(AppSettings.skipSponsorParts.value),
        skippedIds = skippedSegmentIds,
        lastManualSeekAtMs = lastManualSeekAtMs,
        lastManualSeekPositionMs = lastManualSeekPositionMs,
        nowMs = now,
    )
    when (action) {
        is SkipAction.None -> Unit
        is SkipAction.Leave -> skippedSegmentIds += action.segment.id
        is SkipAction.Skip -> {
            skippedSegmentIds += action.segment.id
            skipSeekAtMs = now
            skipSeekTargetMs = action.segment.endMs
            exoPlayer.seekTo(exoPlayer.currentMediaItemIndex, action.segment.endMs)
            Toast.makeText(applicationContext, R.string.skip_segment_done, Toast.LENGTH_SHORT)
                .show()
        }
}
}
