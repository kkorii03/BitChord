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
        // Release builds echo too, so `adb logcat` on the listener's phone shows
        // why a lyric or a video timing went the way it did: warnings and errors
        // from everything, chatter only from the paths still being tuned, each
        // line cut short (a search response can run to pages).
        TrackLog.echo = { level, tag, message, error ->
            // `adb shell setprop log.tag.BitChord DEBUG` opens a tag's chatter on a
            // release build too, for a phone being diagnosed over USB.
            if (BuildConfig.DEBUG || level != 'D' || tag in RELEASE_DEBUG_TAGS || Log.isLoggable(tag.take(23), Log.DEBUG)) {
                val text = if (BuildConfig.DEBUG || message.length <= RELEASE_LINE_CHARS) message else message.take(RELEASE_LINE_CHARS) + "…"
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
        InnerTubeXResolver.debugLogging = BuildConfig.DEBUG
    }

    /** Debug lines a release build still echoes to logcat. */
    private val RELEASE_DEBUG_TAGS = setOf(
        "LyricsTiming", "AiLyrics", "VideoAudio", "VersionAudioAligner", "BitChordAudioDecoder",
        "CanvasRepository", "AppleMusicCanvas", "CanvasArtworkPlayer",
    )
    private const val RELEASE_LINE_CHARS = 1_000

    /** What `InnerTubeXResolver.init(context)` did when it lived in the app. */
    fun initInnerTubeX(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)
        // BotGuard PoTokens minted in a hidden WebView; what WEB_REMIX needs for age-restricted tracks.
        val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
        val poTokens = PoTokenGenerator(
            createMinter = { PoTokenWebView.getNewPoTokenGenerator(app) },
            available = { webViewSupported },
        )
        InnerTubeXResolver.init(
            filesDir = app.filesDir,
            store = object : InnerTubeXResolver.ConfigStore {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
                override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
                override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
            },
            poTokenProvider = poTokens.asTokenProvider(),
        )
    }
}
