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
 * Re-arms AutoPlay for a queue whose last item could not play. The append
 * is asynchronous; [pendingAutoplayAdvanceFor] lets the existing timeline
 * callback move to the new item when it arrives, without polling.
 *
 * @return true when a refill was requested or is already in flight, so the
 *   caller does not report the end of the queue yet.
 */
internal fun PlaybackService.koTryAutoplayRefillBeforeStopping(exoPlayer: ExoPlayer, mediaId: String): Boolean {
    val party = ListenTogether.state.value
    if (!autoplayEnabled(party)) return false
    if (exoPlayer.repeatMode == Player.REPEAT_MODE_ALL) return false
    if (party.inParty && autoplaySupplierId(party) != party.you?.memberId) return false
    if (boundedContextWindow != null && !boundedAutoplayEligible(exoPlayer)) return false
    pendingAutoplayAdvanceFor = mediaId
    pendingAutoplayAdvanceTarget = null
    // An active job may belong to the track that was current a moment
    // ago: it exits on its own id check, but while it is still active it
    // would keep this refill from ever starting. Only a load already
    // running for this same track is the refill in flight.
    if (autoplayRefillNeedsLoad(
            loadActive = autoplayLoadJob?.isActive == true,
            loadedSeed = autoplaySeed,
            mediaId = mediaId,
        )
    ) {
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        loadAutoplayForCurrentTrack()
    }
    return true
}

/**
 * A definitive refusal: use an already-found or newly searched song
 * version — only for a track that never made a sound — swap it into this
 * queue position and play it from the start. Skip when none is available.
 *
 * The failed id is already in [StreamResolver]'s unplayable cache, so it
 * is never offered back by the search. A definitive verdict never re-asks
 * the same id: the call site gates [trySameVideoFirst] with the verdict so
 * an HTTP status error cannot spend the retry budget on it.
 *
 * @param allowAlternateRecording false for a mid-song failure. Another
 *   upload is a different recording, and swapping it in under a listener
 *   who is already hearing the first one is the change this path must not
 *   make; for that failure the next queue item is the replacement.
 */
internal fun PlaybackService.koSwitchToAlternateOrSkip(
    mediaId: String,
    song: Song,
    uri: Uri?,
    reason: String,
    networkFault: Boolean,
    trySameVideoFirst: Boolean = false,
    allowAlternateRecording: Boolean = true,
) {
    val service = this
    if (!alternateLookups.add(mediaId)) return
    // The selection deadline, not the failure handler's clock. The resolver
    // has already spent part of the listener's nine seconds before this
    // coroutine starts; measuring from here restarted the catalogue steps
    // with a fresh budget and let one blocked start run past the ceiling
    // (9 s in the resolver, then 5.5 s more here).
    val selectionStartedAt = trackSelectedAt ?: SystemClock.elapsedRealtime()
    val rescue = BlockedStartRescue(
        startedAtMs = selectionStartedAt,
        nowMs = { SystemClock.elapsedRealtime() },
    )
    scope.launch(TrackLog.about(mediaId)) {
        try {
            // A 403 in the middle of a track is not a verdict about the
            // videoId: the URL expired or its client was refused. Ask for
            // the same id again — another client, then the proxy — before
            // spending the skip. Only then is the track actually gone.
            if (trySameVideoFirst && StreamRefusalRecovery.canAttempt(mediaId)) {
                StreamRefusalRecovery.noteAttempt(mediaId)
                val previous = StreamChoice.of(mediaId)?.url
                    ?: StreamContainer.servedUrl(mediaId)
                fun acceptable(candidate: StreamResolver.MidPlayStream?): StreamResolver.MidPlayStream? =
                    candidate?.takeIf {
                        previous == null ||
                            (it.url != previous && StreamContainer.sameRendition(previous, it.url))
                    }
                val recovered = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(PlaybackService.MID_PLAY_RESOLVE_TIMEOUT_MS) {
                        val direct = StreamResolver.reResolveTrack(
                            mediaId,
                            forceProxy = false,
                            refusedUrl = previous,
                        )
                        acceptable(direct) ?: acceptable(
                            StreamResolver.reResolveTrack(
                                mediaId,
                                forceProxy = true,
                                refusedUrl = previous,
                            ),
                        )
                    }
                }
                if (recovered != null) {
                    StreamRefusalRecovery.clear(mediaId)
                    StreamResolver.rememberServed(mediaId, recovered.url)
                    withContext(Dispatchers.Main) {
                        val live = service.player ?: return@withContext
                        val currentItem = live.currentMediaItem ?: return@withContext
                        if (currentItem.mediaId != mediaId) return@withContext
                        val at = live.currentPosition.coerceAtLeast(0L)
                        // Pin the replacement, or the resolver would hand
                        // back the URL that was just refused.
                        StreamChoice.remember(
                            mediaId,
                            SourceStream(recovered.url, headers = recovered.headers),
                            substituted = false,
                        )
                        StreamContainer.served(mediaId, recovered.url)
                        TrackLog.w(
                            "BitChord",
                            "stream: $mediaId refused mid-play at ${at}ms; " +
                                "re-resolved via ${if (recovered.viaProxy) "proxy" else recovered.client} " +
                                "and resumed",
                            about = mediaId,
                        )
                        retryingMediaId = mediaId
                        live.seekTo(live.currentMediaItemIndex, at)
                        live.prepare()
                    }
                    return@launch
                }
                TrackLog.w(
                    "BitChord",
                    "stream: $mediaId refused mid-play; " +
                        "re-resolve via direct and proxy failed or changed the rendition",
                    about = mediaId,
                )
            } else if (trySameVideoFirst) {
                TrackLog.w(
                    "BitChord",
                    "stream: $mediaId refused mid-play; recovery budget already spent",
                    about = mediaId,
                )
            }
            // Walk the policy's order rather than a private one. Both
            // rungs are measured from the selection deadline above, so the
            // resolver's share comes out of the same nine seconds and the
            // rescue cannot add a fresh 2.5 s + 3 s after it.
            val plan = BlockedStartPolicy.plan(
                // The proxy rung runs inside StreamResolver, not here.
                proxyAvailable = false,
                highQualityAvailable = true,
            )
            var alternate: Song? = null
            var highQuality: SourceStream? = null
            for (rung in plan) {
                when (rung.step) {
                    BlockedStartStep.ALTERNATE -> {
                        alternate = if (allowAlternateRecording) {
                            withContext(Dispatchers.IO) {
                                // The resolver has already ruled the id out,
                                // so the cheapest rescue is a song version
                                // this run already found — including one that
                                // landed after the pre-audio lookup timed
                                // out. Only when there is none is a search
                                // spent, and that search is one bounded
                                // song-version lookup rather than the old
                                // multi-query alternate walk.
                                val downloaded = song.localUri != null ||
                                    Downloads.isDownloaded(song.videoId)
                                val cached = YtMusicRepository.cachedSongVersionFor(song)
                                val fromCache = UnplayableAlternate.cachedSongVersion(
                                    row = song,
                                    cached = cached,
                                    downloaded = downloaded,
                                    unplayable = cached?.let {
                                        StreamResolver.isUnplayable(it.videoId)
                                    } == true,
                                )
                                // A copy already in hand is used even when
                                // the budget is spent: it needs no request
                                // and can make a sound immediately.
                                fromCache ?: if (!UnplayableAlternate.allowsSongVersion(
                                        song,
                                        downloaded,
                                    )
                                ) {
                                    null
                                } else {
                                    // One bounded lookup, inside the same
                                    // nine seconds the resolver has been
                                    // spending. A late answer is discarded
                                    // by the rescue clock.
                                    rescue.runStep(BlockedStartStep.ALTERNATE) {
                                        YtMusicRepository.songVersion(song, beforeAudio = true)
                                            ?.let { found ->
                                                UnplayableAlternate.cachedSongVersion(
                                                    row = song,
                                                    cached = found,
                                                    downloaded = downloaded,
                                                    unplayable = StreamResolver.isUnplayable(
                                                        found.videoId,
                                                    ),
                                                )
                                            }
                                    }
                                }
                            }
                        } else {
                            null
                        }
                        TrackLog.d(
                            "BitChord",
                            BlockedStartPolicy.logLine(
                                mediaId,
                                BlockedStartStep.ALTERNATE,
                                when {
                                    alternate != null -> "ok:${alternate.videoId}"
                                    allowAlternateRecording -> "none"
                                    else -> "skipped"
                                },
                                SystemClock.elapsedRealtime() - selectionStartedAt,
                            ),
                            about = mediaId,
                        )
                    }
                    BlockedStartStep.HIGH_QUALITY -> {
                        // Another upload was not available. A high-quality
                        // source is the next thing that can make a sound:
                        // YouTube's own stream is the one this network
                        // refused, so waiting for it again only repeats the
                        // refusal. This is the one live-path search allowed
                        // before the first note, and only for a row that
                        // has not made one — a mid-song rescue still may
                        // not become another recording. `hq-verify` still
                        // runs afterwards, while the listener hears it, and
                        // can refuse the copy under the existing rules.
                        if (BlockedStartPolicy.shouldSearchHighQuality(
                                alternateFound = alternate != null,
                                allowAlternateRecording = allowAlternateRecording,
                                networkFault = networkFault,
                                definitelyGone = UnplayableReason.isDefinitelyGone(reason),
                            )
                        ) {
                            highQuality = withContext(Dispatchers.IO) {
                                val target = uri?.let { SourceResolver.targetIn(it) }
                                // A catalogue rescue is only for a YouTube
                                // watch row, and never for a row the listener
                                // picked by hand: that row is not up for
                                // substitution (see MIX_PARAM).
                                val youtubeRow = uri != null &&
                                    uri.scheme == "bitchord" &&
                                    uri.authority == "watch" &&
                                    !SourceResolver.isPickedAddonRow(uri)
                                val found = if (!youtubeRow ||
                                    target == null || !SourceResolver.canSubstituteForYouTube()
                                ) {
                                    null
                                } else {
                                    // The protected high-quality window is
                                    // paid from the same nine seconds, so
                                    // the listener hears this copy or the
                                    // next row rather than a search that
                                    // outlives the budget.
                                    rescue.runStep(BlockedStartStep.HIGH_QUALITY) { budgetMs ->
                                        SourceResolver.blockedStartStream(target, budgetMs)
                                    }
                                }
                                TrackLog.d(
                                    "BitChord",
                                    BlockedStartPolicy.logLine(
                                        mediaId,
                                        BlockedStartStep.HIGH_QUALITY,
                                        if (found != null) {
                                            "ok:${found.sourceConfigId ?: "source"}"
                                        } else {
                                            "none"
                                        },
                                        SystemClock.elapsedRealtime() - selectionStartedAt,
                                    ),
                                    about = mediaId,
                                )
                                found
                            }
                        }
                    }
                    else -> Unit
                }
            }
            withContext(Dispatchers.Main) {
                val live = service.player ?: return@withContext
                val currentItem = live.currentMediaItem ?: return@withContext
                if (currentItem.mediaId != mediaId) return@withContext
                val elapsedMs = SystemClock.elapsedRealtime() - selectionStartedAt
                if (highQuality != null) {
                    val target = uri?.let { SourceResolver.targetIn(it) }
                    val currentUri = currentItem.localConfiguration?.uri
                    if (target != null && currentUri != null) {
                        val wasPlaying = live.playWhenReady
                        QualityUpgrade.force(mediaId, highQuality)
                        StreamChoice.remember(mediaId, highQuality, substituted = true)
                        blockedStartCandidates[mediaId] =
                            BlockedStartCandidate(target, highQuality)
                        // Same reason as a mid-song upgrade: replacing the
                        // item is reported as a playlist change, and this
                        // row must not be booked twice before its first
                        // note.
                        swappingMediaId = mediaId
                        live.replaceMediaItem(
                            live.currentMediaItemIndex,
                            currentItem.buildUpon()
                                .setUri(QualityUpgrade.upgradedUri(currentUri.toString()))
                                .setMimeType(null)
                                .build(),
                        )
                        live.seekTo(live.currentMediaItemIndex, 0L)
                        if (wasPlaying) live.play()
                        live.prepare()
                        TrackLog.w(
                            "BitChord",
                            "unplayable: $mediaId $reason -> high-quality start",
                            about = mediaId,
                        )
                        return@withContext
                    }
                }
                val decision = UnplayableRecovery.decide(
                    definitive = true,
                    hasAlternate = alternate != null,
                    consecutiveVerdictSkips = consecutiveVerdictSkips,
                )
                if (decision != UnplayableRecoveryAction.SWAP_ALTERNATE || alternate == null) {
                    val skipReason = verdictSkipReason(reason)
                    if (skipReason == null) {
                        TrackLog.w(
                            "BitChord",
                            "unplayable: $mediaId $reason -> verdict cap reached, staying",
                            about = mediaId,
                        )
                        koReportPlaybackFailure(mediaId, R.string.playback_error)
                        return@withContext
                    }
                    TrackLog.w("BitChord", "unplayable: $mediaId $reason -> skip", about = mediaId)
                    TrackLog.i(
                        "BitChord",
                        "unplayable timing: $mediaId skip after ${elapsedMs}ms",
                        about = mediaId,
                    )
                    TrackLog.i(
                        "BitChord",
                        BlockedStartPolicy.logLine(
                            mediaId,
                            BlockedStartStep.NEXT_TRACK,
                            "skip",
                            SystemClock.elapsedRealtime() - selectionStartedAt,
                        ),
                        about = mediaId,
                    )
                    skipPastUnplayable(mediaId, skipReason)
                    return@withContext
                }
                TrackLog.w(
                    "BitChord",
                    "unplayable: $mediaId $reason -> alt ${alternate.videoId}",
                    about = mediaId,
                )
                TrackLog.i(
                    "BitChord",
                    "unplayable timing: $mediaId alt ${alternate.videoId} after ${elapsedMs}ms",
                    about = mediaId,
                )
                val wasPlaying = live.playWhenReady
                live.replaceMediaItem(
                    live.currentMediaItemIndex,
                    currentItem.koWithAlternateRecording(alternate),
                )
                live.seekTo(live.currentMediaItemIndex, 0L)
                if (wasPlaying) live.play()
                live.prepare()
            }
            // The swap or skip above already happened, so this wait costs
            // the listener nothing. Media3 lets go of a cache entry as the
            // source is released, not as prepare() returns; discarding
            // immediately left the bad bytes in place for the next
            // attempt, and AudioCache logged the key as still in use.
            uri?.let { stale ->
                withContext(Dispatchers.IO) {
                    delay(PlaybackService.RECOVERY_DELAY_MS)
                    AudioCache.discard(stale, keepComplete = networkFault)
                }
            }
        } finally {
            alternateLookups.remove(mediaId)
        }
    }
}

/**
 * Proves a high-quality stream that was allowed to make the first sound of
 * a blocked track. This is a playback event, not a timer: it runs once, on
 * the transition to playing, and never polls. A missing YouTube reference
 * (the blocked case) is not a rejection under [HqAudioVerify]'s rules, so
 * the copy keeps playing; only a measured mismatch takes it off the
 * speaker.
 */
internal fun PlaybackService.koVerifyBlockedStartCandidate(exoPlayer: ExoPlayer) {
    val mediaId = exoPlayer.currentMediaItem?.mediaId ?: return
    val service = this
    val candidate = blockedStartCandidates.remove(mediaId) ?: return
    scope.launch(TrackLog.about(mediaId)) {
        val outcome = HqAudioVerify.verifyOutcome(
            service,
            mediaId,
            candidate.target,
            candidate.stream,
        )
        if (outcome !is HqAudioVerify.Outcome.Mismatch) return@launch
        withContext(Dispatchers.Main) {
            val live = player ?: return@withContext
            if (live.currentMediaItem?.mediaId != mediaId) return@withContext
            TrackLog.w(
                "BitChord",
                "blocked-start: $mediaId hq-verify refused the high-quality start; moving on",
                about = mediaId,
            )
            QualityUpgrade.refuseUpgrades(mediaId)
            QualityUpgrade.forget(mediaId)
            skipPastUnplayable(mediaId, "high-quality start did not match the recording")
        }
    }
}

/**
 * [this] song swapped onto [alternate] while keeping everything that says
 * where the queue entry came from: tier, entry id, radio station and the
 * page that queued it. Only the recording's own fields come from the
 * alternate.
 */
internal fun Song.koWithAlternateRecording(alternate: Song): Song = copy(
    videoId = alternate.videoId,
    title = alternate.title,
    artist = alternate.artist,
    thumbnailUrl = alternate.thumbnailUrl ?: thumbnailUrl,
    durationText = alternate.durationText,
    // The alternate is another recording of this song, so a sparse row
    // must not drop the channel or release the queue entry already knew.
    artistId = alternate.artistId ?: artistId,
    artists = alternate.artists.ifEmpty { artists },
    albumId = alternate.albumId ?: albumId,
    albumName = alternate.albumName ?: albumName,
    isVideo = alternate.isVideo,
    setVideoId = null,
    localUri = null,
    downloadFormat = null,
    localPath = null,
    localDateAddedSeconds = null,
    localDateModifiedSeconds = null,
    trackNumber = null,
    sourceQuality = alternate.sourceQuality,
    sourceHiRes = alternate.sourceHiRes,
    isExplicit = alternate.isExplicit,
)

/**
 * The queue item rebuilt on [alternate] without losing where it sits in
 * the bounded context: entry id, canonical index, cycle and tier are all
 * carried over from [this], exactly as an in-place edit would.
 */
internal fun MediaItem.koWithAlternateRecording(alternate: Song): MediaItem =
    QueueShuffle.withQueueMetadata(
        toSong().koWithAlternateRecording(alternate).toMediaItem(),
        newEntryId = queueEntryId,
        newCanonicalIndex = canonicalIndex,
        newTier = queueTier,
        newContextCycleId = contextCycleId,
    )

/**
 * Says, once per track, that it could not be played.
 *
 * A toast rather than a notification or an in-app banner because this
 * service outlives the screen: the queue can be skipping a dead track with
 * the app in the background or the phone in a pocket, and the toast is the
 * one surface that reaches the listener in every one of those states
 * without asking for `POST_NOTIFICATIONS` first.
 */
internal fun PlaybackService.koReportPlaybackFailure(mediaId: String, message: Int) {
    if (reportedPlaybackFailureId == mediaId) return
    reportedPlaybackFailureId = mediaId
    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
}

/**
 * Why [error] means "never", or null if it only means "not just now".
 *
 * Unwrapped by hand because the classification is the resolver's and the
 * exception has been through Media3's loader by the time it arrives:
 * `ExoPlaybackException` wrapping `Loader.UnexpectedLoaderException` wrapping
 * what the resolver actually threw. A shallow `is` check sees only the
 * outermost of those three.
 */
internal fun PlaybackService.koIsBotChallenge(error: Throwable?): Boolean {
    var cause = error
    var depth = 0
    while (cause != null && depth++ < PlaybackService.PERMANENT_CAUSE_DEPTH) {
        if (cause is StreamResolver.BotChallengeException) return true
        if (UnplayableReason.isBotChallenge(cause.message)) return true
        cause = cause.cause?.takeIf { it !== cause }
    }
    return false
}
