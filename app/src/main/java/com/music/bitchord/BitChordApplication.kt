package com.music.bitchord

import android.app.Application
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.LocalPlay
import com.music.bitchord.data.LocalPlayHistory
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.lyrics.LyricsTranslation
import androidx.appcompat.app.AppCompatDelegate
import com.music.bitchord.ui.player.AndroidPlayerHost
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.data.canvas.CanvasCache
import com.music.bitchord.data.smb.SmbCoverFetcher
import com.music.bitchord.data.webdav.WebDavCoilAuth
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.LastPlayed
import com.music.bitchord.playback.BoundedContextPersistence
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.AndroidStreamHooks
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.PlaylistSync
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

class BitChordApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    /** Asked for on first use rather than at launch; see [PlaylistSync.init]. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /** Foreground/background transitions, logged as they happen rather than sampled. */
    private var startedActivities = 0

    private val activityLifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: android.app.Activity) {
            if (startedActivities++ == 0) {
                TrackLog.i("BitChord", "lifecycle: foreground")
            }
        }

        override fun onActivityResumed(activity: android.app.Activity) = Unit

        override fun onActivityPaused(activity: android.app.Activity) = Unit

        override fun onActivityStopped(activity: android.app.Activity) {
            if (startedActivities > 0 && --startedActivities == 0) {
                TrackLog.i("BitChord", "lifecycle: background")
                // The last chance to put the tail on disk while the app is out
                // of sight; the writer would get there anyway, but a process
                // killed in the background may not leave it that long.
                TrackLog.flush()
                com.music.bitchord.data.FeatureHealthCounters.flush()
            }
        }

        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }

    @OptIn(UnstableApi::class)
    @kotlin.OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        // First, before anything below can fail: a crash during startup has to
        // leave the same evidence as one while a track plays.
        val logDirectory = File(filesDir, "logs")
        TrackLog.initPersistence(logDirectory)
        com.music.bitchord.data.FeatureHealthCounters.init(File(logDirectory, "feature-health"))
        installCrashHandler()
        // Reading and rewriting the warm-start marker is disk I/O on the launch
        // path. The line it emits is diagnostic, so it can land a moment later
        // without holding up the first frame.
        CoroutineScope(Dispatchers.IO).launch { logProcessStart(logDirectory) }
        registerActivityLifecycleCallbacks(activityLifecycle)
        AppForeground.install(this)
        TrackLog.i("BitChord", "lifecycle: app created")

        // The player is drawn by the shared UI module; this is what it reads
        // underneath — settings, the Canvas decoder, outputs, the party.
        AndroidStreamHooks.installEarly()
        PlayerPlatform.install(AndroidPlayerHost(this))
        com.music.bitchord.ui.AppUi.install(com.music.bitchord.ui.AndroidAppUiHost)
        // The shared data layer (lyrics, the YouTube Music client) logs to
        // logcat on debug builds only, as the app's own DebugLog always has.
        if (BuildConfig.DEBUG) {
            DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
                when (level) {
                    'D' -> android.util.Log.d(tag, message, error)
                    'I' -> android.util.Log.i(tag, message, error)
                    'W' -> android.util.Log.w(tag, message, error)
                    else -> android.util.Log.e(tag, message, error)
                }
            }
        } else {
            // R8 strips android.util.Log from a release build (proguard-rules.pro);
            // this stops Media3 composing the lines it would have handed it.
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
            // java.util.logging reaches logcat without android.util.Log, through
            // the handler Android installs on its root logger — the Discord
            // gateway's connection chatter went out that way.
            java.util.logging.LogManager.getLogManager().reset()
            java.util.logging.Logger.getLogger("").level = java.util.logging.Level.OFF
        }
        // Compose re-votes the window's frame rate from dispatchDraw on every
        // frame (API 35+), and Samsung's View logs each vote together with a
        // stack walk of its caller: measured on an S22, 8,000 lines in two
        // minutes, two stack traces built on the main thread per frame. The app
        // asks for its refresh rate itself (MainActivity.applyPerformanceMode),
        // so those votes buy nothing — and they kept resetting that request.
        ComposeUiFlags.isAdaptiveRefreshRateEnabled = false
        // The Spotify library and import live in the shared module; the tokens are the WebView's.
        SpotifyLibrary.auth = SpotifyLibrary.Auth {
            SpotifyToken.accessToken()?.let { SpotifyLibrary.Tokens(it, SpotifyToken.clientToken()) }
        }
        // The per-app language picker, which YouTube Music's `hl` follows.
        // Full tag (zh-Hant / zh-Hans preserved); Innertube maps to hl/gl.
        Innertube.appLanguage = { AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() }
        migrateScriptOnlyAppLocale()
        // The content country, which YouTube Music's `gl` follows. Read live,
        // so a change applies from the next request.
        Innertube.contentCountry = { AppSettings.contentCountry.value }
        // Channels marked as cover channels read their titles as covers.
        com.music.bitchord.data.cover.CoverTitles.designatedChannels = { AppSettings.coverChannels.value }
        // Singers seen on cover titles, so their clips without "Cover" in the title read as theirs too.
        com.music.bitchord.data.cover.CoverTitles.knownSingers = { AppSettings.coverSingers.value }
        com.music.bitchord.data.cover.CoverTitles.onSingerSeen = AppSettings::rememberCoverSinger
        // A cover whose original has been found is shown under the record's own spelling.
        com.music.bitchord.playback.smart.CoverOriginals.init(this)
        com.music.bitchord.data.cover.CoverTitles.knownOriginal = com.music.bitchord.playback.smart.CoverOriginals::get
        // Lyrics no source has: Gemini writes them down off the video.
        com.music.bitchord.playback.smart.AiLyrics.init(this)
        LyricsTranslation.cacheDir = cacheDir
        LyricsTranslation.keepDir = filesDir
        // PlaybackService shares this process, so seeding the cookie here means
        // stream resolution is authenticated from the first play onwards.
        authStore = AuthStore(this)
        // The proxy route has to be in YouTubeProxy before this process makes
        // its first YouTube request. Everything below can start such a request
        // — a source open or the session-scope lookup a few lines down — while
        // the full settings read is still running, and a direct first browse
        // from Korea is exactly what stores a GATED verdict even though a
        // working proxy is configured. This is only the small proxy read.
        AppSettings.initProxy(this, authStore)
        // The refused-track memory that decides "proxy first" next launch. It
        // reads one small preference file and installs a debounced writer; no
        // request is made from here.
        com.music.bitchord.data.net.ProxyPlaybackStore.init(this)
        // Until when the counterpart endpoint is not asked again (a week after
        // five empty answers). One small preference file, no request.
        com.music.bitchord.data.net.CounterpartStore.init(this)
        // Opened off the main thread, alongside the stores below: none of these
        // reads a setting or the session, and between them they are the slowest
        // opens at startup — SourceRegistry's encrypted store most of all.
        // Started only once [AuthStore] exists, because both encrypted stores
        // share one keystore master key and a first launch must not have two
        // threads racing to create it. Nothing here is joined: a store that is
        // asked for before its open finished opens itself on the asking thread
        // (see StartupWork), and the startup thread is serial so a first launch
        // still never has two Keystore creations running at once.
        SourceRegistry.init(this)
        // Per-addon server success counts, response times and rests survive a
        // restart, so the first track of a new session already steers around a
        // host that was asleep or rate limiting yesterday. The first
        // getSharedPreferences open can wait on a disk parse, so it runs on the
        // startup thread; a request that arrives before it finishes simply
        // starts with an empty gate and is merged into by restore().
        StartupWork.background("hq-host-health") {
            com.music.bitchord.playback.HqHostTracker.init(this)
        }
        // Cheap here (it only records paths and starts its own warm-up job), and
        // it has to be ready before the first resolution, which can be the
        // restored queue playing the moment the service starts.
        // The music-gate override is a debug-build tool; tell the shared gate
        // before it reads its persisted flag so a release build ignores one.
        com.music.bitchord.data.innertube.MusicGateFallback
            .setDebugOverridesAllowed(BuildConfig.DEBUG)
        AndroidStreamHooks.initInnerTubeX(this)
        // Its own directory: canvas clips are looping video, not audio, and
        // belong in a cache AudioCache's own limit and eviction policy were
        // never sized for. See CanvasCache's doc for why this one exists at
        // all — it is the fix for canvas clips re-fetching the same few
        // seconds of video from the network on every loop.
        CanvasCache.init(this)
        // Migration-safe: an old single cookie becomes the first encrypted
        // session, while newer installs restore the profile the listener chose.
        val restoredSession = authStore.activeSession
        if (restoredSession != null && authStore.activeAccountId == null) {
            authStore.select(restoredSession.accountId, restoredSession.activeProfileId)
        }
        authStore.cookie = restoredSession?.cookie
        Innertube.cookie = restoredSession?.cookie
        // Which account that cookie actually acts as. Read here rather than on
        // demand so the answer is usually in hand before the first request needs
        // it: a play registered under the wrong account is indistinguishable, to
        // the listener, from one that was never registered at all. Fire and
        // forget — every caller works without it, just less precisely.
        if (restoredSession != null) {
            // After the cookie, never before: setting the cookie clears any
            // channel the last session was acting as, so restoring the choice
            // first would restore it into the value about to be wiped.
            restoredSession.profiles.firstOrNull { it.profileId == restoredSession.activeProfileId }
                ?.let { Innertube.selectChannel(it.pageId, it.dataSyncId, it.authUser) }
            CoroutineScope(Dispatchers.IO).launch { Innertube.ensureSessionScope() }
        }
        AppSettings.init(this, authStore)
        // After AppSettings, whose flows it feeds: the power-save switch and
        // the battery can change while the app is in the background.
        com.music.bitchord.playback.LowPowerDevice.start(this)
        // The screen state the playback service's receiver will keep current;
        // read once here so analysis started before the service is up (or in a
        // process reused with the screen already off) is not misinformed.
        com.music.bitchord.playback.PowerState.initialize(this)
        // Before anything resolves a track: an addon with `checkValidLossless`
        // is gated on this, and the gate reads "no" until it has looked.
        com.music.bitchord.playback.audio.LosslessOutput.init(this)
        // Restores a party this device is still a member of, so a process death
        // mid-session is something the rest of the party never sees. The socket
        // and the clock offset are not restored — both are re-established on
        // the next connect, which is the only way to be sure they are current.
        ListenTogether.init(this)
        SearchHistory.init(this)
        LastPlayed.init(this)
        BoundedContextPersistence.init(this)
        com.music.bitchord.playback.PartyPersonalQueueStash.init(this)
        // SponsorBlock's cache of the segments each video has, read by
        // PlaybackService when a track starts — see
        // [com.music.bitchord.playback.SkipSegments].
        com.music.bitchord.playback.SkipSegments.init(this)
        // Which tracks the listener has reverted to YouTube's own upload. Read
        // by [Song.toMediaItem], so it has to be open before the restart
        // snapshot below is turned back into queue items.
        OriginalVersion.init(this)
        // What's already saved to Downloads, so the song menu can say so
        // without a media-store query per row.
        Downloads.init(this)
        // After both of the things a check reads: the setting and the record.
        PlaylistSync.init(this)
        // The device's own listening record. Opened here rather than in
        // PlaybackService because the Replay page reads it from the UI side and
        // both live in this process — one owner, one directory.
        ListeningStats.init(this)
        // The same record, handed to the shared youtube.com fallback so its
        // Recently played shelf prefers what this device played as music.
        LocalPlayHistory.install(object : LocalPlayHistory.Source {
            override suspend fun recent(limit: Int): List<LocalPlay> =
                ListeningStats.recentTracks(limit).map { LocalPlay(it.asSong(), it.last) }

            override suspend fun topArtists(days: Int, minPlays: Int) =
                ListeningStats.topArtists(days, minPlays)

            override suspend fun rediscover(limit: Int, olderThanDays: Int) =
                ListeningStats.rediscover(limit, olderThanDays)

            override fun changes() = ListeningStats.changes
        })
        // The strapline under the old Listen again shelf: the account the
        // session is acting as, not a fresh network identity lookup.
        LocalPlayHistory.installAccountName(LocalPlayHistory.AccountNameSource {
            val session = authStore.activeSession
            session?.profiles?.firstOrNull { it.profileId == session?.activeProfileId }
                ?.name?.takeIf { it.isNotBlank() }
                ?: session?.name?.takeIf { it.isNotBlank() }
                ?: authStore.channelName?.takeIf { it.isNotBlank() }
        })
        // What the recommendation store has learned from this device's
        // listening and ratings. Opened beside the listening record, for the
        // same reason: both the UI and PlaybackService live in this process.
        com.music.bitchord.data.taste.TasteSignals.init(this)
        // The seven-day per-seed cache behind ListenBrainz's similar tracks,
        // read only by the AutoPlay top-up that is already off the main thread.
        com.music.bitchord.data.taste.SimilarCandidates.init(this)
        // After AppSettings, whose switch decides whether half of it runs.
        ArtistFacts.init(this)
        // One cache directory can only be opened once per process, and
        // PlaybackService shares this one — so it's opened here, not there.
        AudioCache.init(this)
        // The offscreen WebView that mints a Spotify access token from the
        // listener's own session cookie needs a Context, and nothing in the
        // suspend call chain that reaches it (a track's canvas lookup) has
        // one to hand — see SpotifyToken's doc for why.
        SpotifyToken.init(this)
        // A sideloaded update is just a new APK over the old one, so app data —
        // including whatever the old build left in the image cache — survives
        // it untouched. Wipe that on the first launch of a higher versionCode so
        // a format change between builds can't serve stale bytes.
        //
        // The song cache is deliberately *not* wiped here any more. It is the
        // Cached songs folder and what plays offline, and clearing it on every
        // update is what made the whole folder disappear overnight whenever a
        // new build was installed. A build that really does change its keys or
        // its bytes says so through AudioCache's own cache format instead.
        if (AppSettings.consumeVersionUpdate(BuildConfig.VERSION_CODE)) {
            SingletonImageLoader.get(this).let { loader ->
                loader.memoryCache?.clear()
                // Deleting the image cache directory is file I/O for however
                // many thousands of files the old build left, and this is
                // onCreate: on the main thread it was part of the first frame
                // after an update. Nothing reads the cache before the first
                // image request, so it can finish in the background.
                CoroutineScope(Dispatchers.IO).launch { loader.diskCache?.clear() }
            }
        }
        // Initialize LastFM with saved settings if available
        initLastfm()
        // The public "apps open right now" count; see Presence.
        com.music.bitchord.data.presence.AndroidPresence.install(this)
        StartupTrace.applicationCreated()
    }

    /**
     * Coil's own transport, separate from [Http][com.music.bitchord.data.Http]'s
     * playback-tuned client.
     *
     * Wikimedia (which serves signature art) answers generic clients with
     * HTTP 403 under its User-Agent policy, so every image request carries an
     * identifying agent. Anything else falls through to Coil's own fetchers.
     */
    private val coilHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "BitChord/${BuildConfig.VERSION_NAME} (Android; +https://github.com/kushagrasinghx/BitChord)",
                        )
                        .build(),
                )
            }
            .build()
    }

    /**
     * Artwork loading, which was previously left entirely on Coil's defaults.
     *
     * The defaults aren't unreasonable, but the disk cache is sized at 2% of
     * free space — which on a full phone is the 10MB floor, a few screens of
     * covers, and covers are exactly the thing worth still having tomorrow.
     * Naming a directory alongside it keeps that cache somewhere identifiable
     * rather than in the process's temp dir.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Covers on the WebDAV server need the credential or every one
            // of them 401s — which reads as "this track has no artwork".
            // Coil's own transport never sees Http.client's interceptor, so
            // the header is attached per request instead. See WebDavCoilAuth.
            .components {
                add(WebDavCoilAuth())
                // Covers filed on the SMB share; anything else falls
                // through to Coil's own fetchers. See SmbCoverFetcher.
                add(SmbCoverFetcher.Factory())
                // Artist signatures are usually SVG, which Coil can't
                // decode without this. See ArtistSignatureRepository.
                add(SvgDecoder.Factory())
                // Wikimedia 403s Coil's default client; see [coilHttpClient].
                add(OkHttpNetworkFetcherFactory(callFactory = { coilHttpClient }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            // Covers arriving with a hard cut read as the list flickering as
            // it scrolls; a short fade reads as them developing.
            .crossfade(200)
            .allowHardware(!emulatedGpu)
            .build()

    /**
     * Whether graphics here go through an emulator's GL translator rather than
     * a real GPU driver — see [newImageLoader].
     *
     * On BlueStacks (Android 9) uploading a hardware bitmap aborts the whole
     * process: the renderer turns a cover into a texture, the translator
     * answers GL_INVALID_ENUM, and hwui's own error check kills the app —
     * `glGenTextures error! GL_INVALID_ENUM`, from RenderThread, each time the
     * app came back to the foreground with a 640px cover to decode. Covers in
     * ordinary bitmaps never take that path.
     *
     * Asked of the driver on disk rather than of build properties, because
     * emulators spoof those — BlueStacks reports itself as a Qualcomm Galaxy
     * S22. `libEGL_emulation` is the AOSP emulator's translator, which no
     * phone ships.
     */
    private val emulatedGpu: Boolean by lazy {
        listOf("/vendor/lib64", "/vendor/lib", "/system/lib64", "/system/lib")
            .any { java.io.File("$it/egl/libEGL_emulation.so").exists() }
    }

    private fun initLastfm() {
        val sessionKey = AppSettings.lastfmSessionKey.value
        if (sessionKey.isBlank()) return
        val endpoint = AppSettings.lastfmEndpoint.value.ifBlank { LastFM.DEFAULT_API_ENDPOINT }
        val apiKey = AppSettings.lastfmApiKey.value.trim()
        val secret = AppSettings.lastfmSecret.value.trim()
        if (apiKey.isBlank() || secret.isBlank()) return
        LastFM.configure(
            endpoint = endpoint,
            apiKey = apiKey,
            secret = secret,
            sessionKey = sessionKey,
        )
    }

    companion object {
        lateinit var authStore: AuthStore
            private set

        /** A previous process this recent is treated as a warm restart, not a cold launch. */
        private const val WARM_RESTART_MS = 10 * 60 * 1000L

        /**
         * One-time move from region-less script tags to regional ones.
         *
         * Early builds of the Simplified/Traditional picker stored `zh-Hant` /
         * `zh-Hans`. Android Views resolve those (script qualifiers), but the
         * shared Compose resources only match language + region — so Settings
         * read Traditional while the player read Simplified. Regional tags
         * match on both systems. Bare `zh` is left alone: it historically
         * meant Simplified.
         */
        private fun migrateScriptOnlyAppLocale() {
            val current = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
            val replacement = when (current) {
                "zh-Hant" -> "zh-TW"
                "zh-Hans" -> "zh-CN"
                else -> return
            }
            AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(replacement),
            )
        }
    }
}

/**
 * Whether a previous process this recent is a warm restart rather than a cold
 * launch. Pulled out so the marker arithmetic can be checked without a file.
 */
internal fun isWarmProcessRestart(nowMs: Long, previousAtMs: Long, windowMs: Long): Boolean =
    previousAtMs > 0L && nowMs - previousAtMs in 0..windowMs

/** The warm-start marker is one line: `<pid> <epoch millis>`. */
internal fun processMarkerLine(pid: Int, nowMs: Long): String = "$pid $nowMs"

/**
 * Reads the timestamp from the marker's first line. The pid and any later lines
 * are ignored, so a file written by an older build still parses.
 */
internal fun processMarkerTimestamp(text: String): Long =
    text.lineSequence().firstOrNull()
        ?.trim()
        ?.split(' ', limit = 2)
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?: 0L
