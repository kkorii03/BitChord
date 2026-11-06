package com.music.bitchord.ko

import com.music.bitchord.playback.TRANSLATION_AHEAD_AI_GRACE_MS
import com.music.bitchord.playback.TRANSLATION_AHEAD_DELAY_MS
import com.music.bitchord.playback.SkipSegments
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.toSong
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.EXTRA_UNVERIFIED_DOWNLOAD

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
 * Hands the next song in the queue to [TranslationAhead] once this one has
 * settled in. Read late rather than now: autoplay fills the queue a moment
 * after a transition, and a song added to play next counts too.
 *
 * Lyrics are always worth fetching ahead; timing and translation only on
 * an unmetered connection when heavy work is allowed (see
 * [TranslationAheadPolicy]). A slot AutoPlay may
 * replace is not prepared at all, and the heavy pass for the track that is
 * actually playing is made when it starts — [scheduleCurrentTranslationAhead].
 */
internal fun PlaybackService.koScheduleTranslationAhead() {
    val service = this
    translationAheadJob?.cancel()
    translationAheadJob = null
    val (next, fromAutoplay) = koUpcomingSong() ?: return
    val plan = TranslationAheadPolicy.plan(
        metered = AppSettings.meteredConnection.value,
        lowPower = AppSettings.lowPower.value,
        allowHeavy = AppSettings.heavyWorkAllowed,
        autoplayUncertain = fromAutoplay,
    )
    if (plan == TranslationAheadPolicy.Plan.NONE) return
    translationAheadJob = scope.launch(Dispatchers.Main) {
        delay(TRANSLATION_AHEAD_DELAY_MS)
        TranslationAhead.nextTrack(
            service,
            next,
            allowHeavy = plan == TranslationAheadPolicy.Plan.FULL,
            paidGate = { koAwaitTranslationAheadAiGate() },
        )
    }
}

/**
 * The next row's paid timing engines wait until the current track has held
 * the listener for [NextPrepPolicy.AI_MIN_PLAYED_MS]. This is the same gate
 * the next-song bundle uses, on the player's own clock: a message at that
 * position wakes the wait, never a polling loop. A track that will end
 * before the gate opens keeps only its free measurement.
 */
internal suspend fun PlaybackService.koAwaitTranslationAheadAiGate(): Boolean =
    withContext(Dispatchers.Main.immediate) {
        val exoPlayer = player ?: return@withContext false
        val played = exoPlayer.currentPosition
        val duration = exoPlayer.duration
        val remaining = if (duration > 0L) {
            NextPrepPolicy.remainingMs(duration, played)
        } else {
            Long.MAX_VALUE
        }
        // The current track's allowance ends twenty seconds before its
        // handoff, so even an already-open gate must not start a paid
        // request inside that window (a short or near-end track).
        if (!NextPrepPolicy.canStartStep(remaining)) return@withContext false
        if (NextPrepPolicy.aiGateOpen(played)) return@withContext true
        val waitMs = NextPrepPolicy.AI_MIN_PLAYED_MS - played
        // Waiting for the gate would only start the request at or after
        // the handoff; keep the free measurement and skip the paid one.
        if (remaining != Long.MAX_VALUE && remaining <= waitMs) {
            return@withContext false
        }
        val reached = CompletableDeferred<Unit>()
        val message = runCatching {
            exoPlayer.createMessage { _, _ -> reached.complete(Unit) }
                .setPosition(NextPrepPolicy.AI_MIN_PLAYED_MS)
                .setDeleteAfterDelivery(true)
                .send()
        }.getOrNull() ?: return@withContext false
        try {
            withTimeoutOrNull(waitMs + TRANSLATION_AHEAD_AI_GRACE_MS) {
                reached.await()
            } != null
        } finally {
            message.cancel()
        }
    }

/**
 * The pass for the track now playing, when the ahead look did not make it:
 * timing and translation start once the song is actually current rather
 * than during the previous one. Skipped when that look already prepared it.
 */
internal fun PlaybackService.koScheduleCurrentTranslationAhead(song: Song) {
    val service = this
    currentTranslationAheadJob?.cancel()
    currentTranslationAheadJob = scope.launch(Dispatchers.Main) {
        delay(TRANSLATION_AHEAD_DELAY_MS)
        TranslationAhead.trackStarted(service, song)
    }
}

/** The next song and whether it is an AutoPlay suggestion that may be replaced. */
internal fun PlaybackService.koUpcomingSong(): Pair<Song, Boolean>? {
    val exoPlayer = player ?: return null
    val index = exoPlayer.nextMediaItemIndex
    if (index == C.INDEX_UNSET || index !in 0 until exoPlayer.mediaItemCount) return null
    val item = exoPlayer.getMediaItemAt(index)
    val song = runCatching { item.toSong() }.getOrNull() ?: return null
    return song to item.fromAutoplay
}

/**
 * Asks the download health check about the track that just became current.
 *
 * One job at a time, for the track in hand. The check is event-driven —
 * this transition is the event — and a file whose version was already
 * checked returns immediately. A wrong verdict swaps the item onto the
 * song-version stream at the same position; the repair itself is queued by
 * [DownloadHealth].
 */
internal fun PlaybackService.koScheduleDownloadHealthCheck(song: Song?, player: ExoPlayer) {
    val service = this
    if (song == null || song.videoId.isBlank()) return
    // A replay on the file finds the stream's choice from the play before, for fifteen minutes.
    if (song.localUri != null && StreamChoice.isYouTube(song.videoId)) StreamChoice.forget(song.videoId)
    downloadHealthJob?.cancel()
    downloadHealthJob = scope.launch(TrackLog.about(song.videoId)) {
        val outcome = DownloadHealth.onPlayback(service, song) ?: return@launch
        val wrong = outcome.verdict == com.music.bitchord.data.sources.DownloadHealthPolicy.Verdict.WRONG_RECORDING ||
            outcome.verdict == com.music.bitchord.data.sources.DownloadHealthPolicy.Verdict.ARRANGEMENT
        // Only a stream verdict means the network can stand in. Offline the
        // policy answers REDOWNLOAD, and swapping the condemned local file
        // for a stream that cannot open would turn a wrong recording into
        // no playback at all; keep the local file until the repair lands.
        val canStream = outcome.action == com.music.bitchord.data.sources.DownloadHealthPolicy.Action.STREAM
        withContext(Dispatchers.Main) {
            val live = service.player ?: return@withContext
            val item = live.currentMediaItem ?: return@withContext
            if (item.mediaId != song.videoId) return@withContext
            val position = live.currentPosition
            if (wrong) {
                // The file is already behind the stream when it was unverified;
                // there is no restart to make. A file that was playing itself
                // is swapped in place at the same position.
                if (!canStream || song.localUri == null) return@withContext
                koRestreamWrongRecording(live, item, position)
                return@withContext
            }
            if (outcome.verdict == com.music.bitchord.data.sources.DownloadHealthPolicy.Verdict.OK &&
                item.mediaMetadata.extras?.getBoolean(EXTRA_UNVERIFIED_DOWNLOAD) == true
            ) {
                koUseVerifiedDownload(live, item, position)
            }
        }
    }
}

/**
 * Moves a playing unverified download from its stream onto the file the check
 * just accepted, at the position the listener has reached.
 */
internal fun PlaybackService.koUseVerifiedDownload(
    player: ExoPlayer,
    item: MediaItem,
    position: Long,
): Boolean {
    val id = item.mediaId
    val file = Downloads.verifiedSavedUri(id) ?: return false
    val verified = item.toSong().copy(localUri = file, localPath = null).toMediaItem()
    if (verified.localConfiguration?.uri == item.localConfiguration?.uri) return false
    TrackLog.i(
        "BitChord",
        "$id recording verified; using the downloaded file",
        about = id,
    )
    swappingMediaId = id
    val replaced = runCatching {
        player.replaceMediaItem(player.currentMediaItemIndex, verified)
    }.isSuccess
    if (!replaced) {
        swappingMediaId = null
        return false
    }
    // Left naming YouTube, the lyric clock said playing=youtube over the file: a 97 %
    // answer heard on it dropped (11-06 MUny_GDYIDM), others moved by its offset (w58uXpyHWes).
    StreamChoice.forget(id)
    player.seekTo(player.currentMediaItemIndex, position)
    player.prepare()
    return true
}

/**
 * Replaces a condemned downloaded file with the song-version stream, in
 * place and at the same position.
 *
 * The file is left on disk, marked wrong, until the repair download
 * commits its replacement — see [Downloads.markWrongRecording]. Rebuilding
 * the item without the local uri sends [Song.toMediaItem] down its
 * streaming branch, and the record's wrong flag keeps every later build
 * from offering the same file again.
 */
internal fun PlaybackService.koRestreamWrongRecording(
    player: ExoPlayer,
    item: MediaItem,
    position: Long,
): Boolean {
    // The download check is about the file, not the recording now coming out
    // of the speaker. An item already carrying an accepted upgrade is that
    // recording; rebuilding it from the song would silently drop the swap and
    // put the listener back on YouTube. The repair still runs from the
    // download record — only this playback handoff is refused.
    val restreamed = item.toSong().copy(localUri = null, localPath = null).toMediaItem()
    if (QualityUpgrade.replacementDropsUpgrade(
            item.localConfiguration?.uri?.toString(),
            restreamed.localConfiguration?.uri?.toString(),
        )
    ) {
        TrackLog.w(
            "BitChord",
            "${item.mediaId} is playing an accepted upgrade; not restreaming the condemned download",
            about = item.mediaId,
        )
        return false
    }
    if (restreamed.localConfiguration?.uri == item.localConfiguration?.uri) return false
    TrackLog.w(
        "BitChord",
        "${item.mediaId} is a wrong recording; streaming the song version instead",
        about = item.mediaId,
    )
    recoveries.remove(item.mediaId)
    // The same song continues from the stream, so the transition bookkeeping
    // must not treat this as the queue moving on (which reset the playhead).
    swappingMediaId = item.mediaId
    val replaced = runCatching {
        player.replaceMediaItem(player.currentMediaItemIndex, restreamed)
    }.isSuccess
    if (!replaced) {
        swappingMediaId = null
        return false
    }
    player.seekTo(player.currentMediaItemIndex, position)
    player.prepare()
    return true
}
