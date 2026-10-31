package com.music.bitchord.data.innertube

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.TrackLog
import android.webkit.CookieManager
import com.music.bitchord.data.innertube.potoken.PoTokenGenerator
import com.music.bitchord.data.innertube.potoken.PoTokenStore
import com.music.bitchord.data.innertube.potoken.PoTokenWebView
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceResolver

/**
 * The phone's answers to what the shared stream resolution asks of its
 * platform: the clock, logcat, the exported log's header, the quality
 * ceiling, and InnerTubeX's config store and BotGuard minter.
 */
object AndroidStreamHooks {

    /** Before anything logs or resolves. */
    fun installEarly() {
        MonotonicClock.nowMs = SystemClock::elapsedRealtime
        // logcat on debug builds only; a release build keeps the lines for Copy
        // Log and writes them nowhere else.
        if (BuildConfig.DEBUG) {
            TrackLog.echo = { level, tag, message, error ->
                when (level) {
                    'D' -> Log.d(tag, text, error)
                    'I' -> Log.i(tag, text, error)
                    'W' -> Log.w(tag, text, error)
                    else -> Log.e(tag, text, error)
                }
            }
        }
        TrackLog.sourcesLine = {
            "sources: substitution=${SourceResolver.canSubstituteForYouTube()} " +
                "request=${SourceResolver.requestForNow()}"
        }
        TrackLog.headerLines = {
            listOf(
                "build: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            )
        }
        StreamResolver.maxKbps = { AppSettings.effectiveAudioQuality.maxKbps }
        // A rate-limited URL must leave the playback layer's pins too, not only
        // the resolver's URL cache: otherwise the next open is handed the same
        // paused URL from StreamChoice/QualityUpgrade without asking again.
        StreamResolver.rateLimitedListener = { videoId, url ->
            if (videoId != null) {
                com.music.bitchord.playback.StreamChoice.forget(videoId)
                com.music.bitchord.playback.QualityUpgrade.forget(videoId)
            }
            // A forced module upgrade has no YouTube id; match its URL instead.
            com.music.bitchord.playback.StreamChoice.forgetUrl(url)
            com.music.bitchord.playback.QualityUpgrade.forgetUrl(url)
        }
        InnerTubeXResolver.debugLogging = BuildConfig.DEBUG
    }

    /** Debug lines a release build still echoes to logcat. */
    private val RELEASE_DEBUG_TAGS = setOf(
        "LyricsTiming", "AiLyrics", "VideoAudio", "VersionAudioAligner", "BitChordAudioDecoder",
        "CanvasRepository", "AppleMusicCanvas", "CanvasArtworkPlayer",
    )
    private const val RELEASE_LINE_CHARS = 1_000

    /** The whole minted PoToken record, read and written atomically. */
    private const val KEY_POTOKEN_RECORD = "potoken_record"

    /** What `InnerTubeXResolver.init(context)` did when it lived in the app. */
    fun initInnerTubeX(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)
        val gatePrefs = app.getSharedPreferences("music_gate_fallback", Context.MODE_PRIVATE)
        MusicGateFallback.init(object : MusicGateFallback.Store {
            override fun getString(key: String): String? = gatePrefs.getString(key, null)
            override fun putString(key: String, value: String) =
                gatePrefs.edit().putString(key, value).apply()
        })
        // BotGuard PoTokens minted in a hidden WebView; what WEB_REMIX needs for age-restricted tracks.
        val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
        val poTokens = PoTokenGenerator(
            createMinter = { PoTokenWebView.getNewPoTokenGenerator(app) },
            available = { webViewSupported },
            cacheStore = object : PoTokenStore {
                // One key and one editor call: a process kill can leave the old
                // record or the new one, never a session/video from one mint
                // paired with tokens from another.
                override fun read(): String? = prefs.getString(KEY_POTOKEN_RECORD, null)
                override fun write(record: String) =
                    prefs.edit().putString(KEY_POTOKEN_RECORD, record).apply()
            },
        )
        Innertube.initVisitorStore(object : Innertube.VisitorStore {
            override fun getString(key: String): String? = prefs.getString(key, null)
            override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
        })
        InnerTubeXResolver.init(
            filesDir = app.filesDir,
            store = object : InnerTubeXResolver.ConfigStore {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
                override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
                override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
            },
            poTokenProvider = poTokens.asTokenProvider(),
            poTokenPrewarm = { visitorData -> poTokens.prewarm(visitorData) },
        )
        // Which client answered last time survives a restart for a day. The
        // direct-path bench is deliberately process-only: it is about this run.
        StreamResolver.initLearning(
            object : ResolutionOrder.Store {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
                override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
                override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
            },
        )
    }
}
