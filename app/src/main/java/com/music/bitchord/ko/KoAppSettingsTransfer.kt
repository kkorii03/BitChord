package com.music.bitchord.ko

import android.content.SharedPreferences
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.settings.EqualizerMode
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.data.settings.LowPowerMode
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.data.settings.TransferApplyResult
import com.music.bitchord.data.settings.TransferCategory
import com.music.bitchord.data.settings.TransferKey
import com.music.bitchord.data.settings.TransferSpec
import com.music.bitchord.data.settings.TransferType
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.PlaybackParametersMath
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.settings.normalize

// Preference names used by the fork's import validation and settings transfer.
// The same values live in AppSettings for the upstream code; these keep the
// fork-side whitelist in this package.
internal const val KEY_QUALITY_LEGACY = "audio_quality"
internal const val KEY_QUALITY_WIFI = "audio_quality_wifi"
internal const val KEY_QUALITY_CELLULAR = "audio_quality_cellular"
internal const val KEY_UPGRADE_LENGTH_SLACK_SECONDS = "upgrade_length_slack_seconds"
internal const val KEY_QUALITY_DOWNLOAD = "audio_quality_download"
internal const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
internal const val KEY_EXPORT_DOWNLOADS = "export_downloads"
internal const val KEY_CROSSFADE = "crossfade_seconds"
internal const val KEY_SMART_FADE = "smart_fade_enabled"
internal const val KEY_AUTOMIX_PERFORMANCE_MODE = "automix_performance_mode"
internal const val KEY_SKIP_SILENCE = "skip_silence"
internal const val KEY_OUTPUT_PCM_MODE = "output_pcm_mode"
internal const val KEY_PREFER_USB_DAC = "prefer_usb_dac"
internal const val KEY_LOUDNESS_NORMALIZATION = "loudness_normalization"
internal const val KEY_LOUDNESS_OFF_ON_SPEAKER = "loudness_off_on_speaker"
internal const val KEY_DOLBY_ATMOS = "dolby_atmos"
internal const val KEY_SPATIAL_AUDIO = "spatial_audio"
internal const val KEY_EQ_ENABLED = "equalizer_enabled"
internal const val KEY_EQ_MODE = "equalizer_mode"
internal const val KEY_EQ_TONE_X = "equalizer_tone_x"
internal const val KEY_EQ_TONE_Y = "equalizer_tone_y"
internal const val KEY_EQ_FOCUSED = "equalizer_focused"
internal const val KEY_EQ_BALANCE = "equalizer_balance"
internal const val KEY_EQ_BANDS = "equalizer_bands"
internal const val KEY_SPEED = "playback_speed"
internal const val KEY_THEME = "theme_mode"
internal const val KEY_AUTOPLAY = "autoplay"
internal const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
internal const val KEY_REPEAT_MODE = "repeat_mode"
internal const val KEY_NERD_STATS = "show_nerd_stats"
internal const val KEY_CACHE_LIMIT = "audio_cache_limit_bytes"
internal const val KEY_REDUCE_ANIMATION = "reduce_animation"
internal const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
internal const val KEY_HIGH_PERFORMANCE_MODE = "high_performance_mode"
internal const val KEY_PERFORMANCE_REFRESH_RATE = "performance_refresh_rate"
internal const val KEY_STOP_ON_TASK_REMOVED = "stop_on_task_removed"
internal const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
internal const val KEY_HIDE_STATUS_BAR_IN_PLAYER = "hide_status_bar_in_player"
internal const val KEY_HIDE_SONG_STATUS = "hide_song_status"
internal const val KEY_SHOW_QUEUE_HISTORY = "show_queue_history"
internal const val KEY_SWIPE_TO_PLAY_NEXT = "swipe_to_play_next"
internal const val KEY_DONT_REPEAT_SUGGESTIONS = "dont_repeat_suggestions"
internal const val KEY_PREFER_MUSIC_ONLY = "prefer_music_only"
internal const val KEY_SMART_VERSION_ALIGNMENT = "smart_version_alignment"
internal const val KEY_REDUCE_BLUR = "reduce_dynamic_blur"
internal const val KEY_LIQUID_GLASS = "liquid_glass"
internal const val KEY_LYRICS_BLUR = "lyrics_blur"
internal const val KEY_LYRICS_OFFSET_MS = "lyrics_offset_ms"
internal const val KEY_TRANSLATION_LANGUAGE = "translation_language"
internal const val KEY_ANIMATED_CANVAS = "animated_canvas"
internal const val KEY_CANVAS_OVER_CELLULAR = "canvas_over_cellular"
internal const val KEY_SPOTIFY_CANVAS_AUTO_HIDE = "spotify_canvas_auto_hide"
internal const val KEY_PRIORITIZE_SPOTIFY_CANVAS = "prioritize_spotify_canvas"
internal const val KEY_SIGNATURE_API_ENABLED = "signature_api_enabled"
internal const val KEY_SIGNATURE_API_URL = "signature_api_url"
internal const val KEY_FULL_BLEED_ARTWORK = "full_bleed_artwork"
internal const val KEY_LEGACY_MESH_GRADIENT = "legacy_mesh_gradient"
internal const val KEY_LAST_PLAYER_SCREEN = "last_player_screen"
internal const val KEY_SYNCED_LYRICS = "synced_lyrics"
internal const val KEY_LYRICS_SOURCES = "lyrics_sources"
internal const val KEY_LYRICS_SOURCES_SEEN = "lyrics_sources_seen"
internal const val KEY_LYRICS_SOURCE_ORDER = "lyrics_source_order"
internal const val KEY_PRIORITIZE_SYLLABLE_SYNC = "prioritize_syllable_sync"
internal const val KEY_PAXSENIX_API_KEY = "paxsenix_api_key"
internal const val KEY_REPLAY_GENRES = "replay_genres"
internal const val KEY_FILTER_NON_MUSIC_AUDIO = "filter_non_music_audio"
internal const val KEY_SHOW_CACHE_FOLDER = "show_cache_folder"
internal const val KEY_LOCAL_MUSIC_SORT = "local_music_sort"
internal const val KEY_DOWNLOADED_MUSIC_SORT = "downloaded_music_sort"
internal const val KEY_LIBRARY_SORT = "library_sort"
internal const val KEY_DETAIL_SONG_SORTS = "detail_song_sorts"
internal const val KEY_LOCAL_MUSIC_VIEW_TYPE = "local_music_view_type"
internal const val KEY_DOWNLOADED_MUSIC_VIEW_TYPE = "downloaded_music_view_type"
internal const val KEY_HOME_RECENTS_VIEW_TYPE = "home_recents_view_type"
internal const val KEY_LOCAL_MUSIC_FOLDER_URI = "local_music_folder_uri"
internal const val KEY_WEBDAV_URL = "webdav_url"
internal const val KEY_WEBDAV_USERNAME = "webdav_username"
internal const val KEY_SMB_HOST = "smb_host"
internal const val KEY_SMB_SHARE = "smb_share"
internal const val KEY_SMB_BASE_PATH = "smb_base_path"
internal const val KEY_SMB_USERNAME = "smb_username"
internal const val KEY_PINNED_PLAYLISTS = "pinned_playlists"
internal const val KEY_LASTFM_ENABLED = "lastfm_enabled"
internal const val KEY_LASTFM_USERNAME = "lastfm_username"
internal const val KEY_LASTFM_SESSION_KEY = "lastfm_session_key"
internal const val KEY_LASTFM_API_KEY = "lastfm_api_key"
internal const val KEY_LASTFM_SECRET = "lastfm_secret"
internal const val KEY_LASTFM_ENDPOINT = "lastfm_endpoint"
internal const val KEY_LASTFM_SCROBBLE_ENABLED = "lastfm_scrobble_enabled"
internal const val KEY_LASTFM_NOW_PLAYING = "lastfm_now_playing"
internal const val KEY_LASTFM_PRIMARY_ARTIST_ONLY = "lastfm_primary_artist_only"
internal const val KEY_SCROBBLE_MIN_DURATION = "scrobble_min_duration"
internal const val KEY_SCROBBLE_DELAY_PERCENT = "scrobble_delay_percent"
internal const val KEY_SCROBBLE_DELAY_SECONDS = "scrobble_delay_seconds"
internal const val KEY_LISTENBRAINZ_ENABLED = "listenbrainz_enabled"
internal const val KEY_LISTENBRAINZ_TOKEN = "listenbrainz_token"
internal const val KEY_LISTENBRAINZ_PRIMARY_ARTIST_ONLY = "listenbrainz_primary_artist_only"
internal const val KEY_SPOTIFY_SPDC_TOKEN = "spotify_spdc_token"
internal const val KEY_DISCORD_USERNAME = "discord_username"
internal const val KEY_DISCORD_NAME = "discord_name"
internal const val KEY_DISCORD_AVATAR = "discord_avatar"
internal const val KEY_DISCORD_RPC_ENABLED = "discord_rpc_enabled"
internal const val KEY_DISCORD_USE_DETAILS = "discord_use_details"
internal const val KEY_DISCORD_SHOW_AUDIO_QUALITY = "discord_show_audio_quality"
internal const val KEY_DISCORD_ADVANCED_MODE = "discord_advanced_mode"
internal const val KEY_DISCORD_STATUS = "discord_status"
internal const val KEY_DISCORD_ACTIVITY_TYPE = "discord_activity_type"
internal const val KEY_DISCORD_ACTIVITY_NAME = "discord_activity_name"
internal const val KEY_DISCORD_BUTTON_1_TEXT = "discord_button_1_text"
internal const val KEY_DISCORD_BUTTON_1_VISIBLE = "discord_button_1_visible"
internal const val KEY_DISCORD_BUTTON_2_TEXT = "discord_button_2_text"
internal const val KEY_DISCORD_BUTTON_2_VISIBLE = "discord_button_2_visible"
internal const val KEY_DISCORD_INFO_DISMISSED = "discord_info_dismissed"
internal const val KEY_LAST_VERSION_CODE = "last_version_code"

private const val MIN_LYRICS_OFFSET_MS = -5_000
private const val MAX_LYRICS_OFFSET_MS = 5_000
private const val MIN_UPGRADE_LENGTH_SLACK_SECONDS = 0
private const val MAX_UPGRADE_LENGTH_SLACK_SECONDS = 10

internal object KoAppSettingsTransfer {

    private val prefs: SharedPreferences get() = AppSettings.prefs

    private val SECRETS get() = AppSettings.SECRETS

    private val DEVICE_LOCAL get() = AppSettings.DEVICE_LOCAL

    fun importPrefs(values: Map<String, Any?>): Boolean {
        if (!AppSettings.prefsReady()) return false
        val kept = prefs.all.filterKeys { it in SECRETS || it in DEVICE_LOCAL }
        val saved = prefs.edit().apply {
            clear()
            val incoming = values
                .filterKeys { it !in SECRETS && it !in DEVICE_LOCAL }
                .mapNotNull { (key, value) ->
                    val sanitized = when {
                        key in KNOWN_PREFS -> sanitizeKnownPref(key, value)
                        value is Float && !value.isFinite() -> null
                        else -> value
                    }
                    sanitized?.let { key to it }
                }
                .toMap()
            (kept + incoming).forEach { (key, value) ->
                putSanitized(key, value)
            }
        }.commit()
        AppSettings.reload()
        return saved
    }

    private enum class PrefCarrier { BOOLEAN, INT, LONG, FLOAT, STRING, STRING_SET }

    private data class KnownPref(
        val carrier: PrefCarrier,
        val allowed: Set<String>? = null,
        val intRange: IntRange? = null,
        val floatRange: ClosedFloatingPointRange<Float>? = null,
    )

    /**
     * Every preference [readAll] knows how to read, with the carrier type it
     * must have and any bound its own setter enforces.
     *
     * A restored value is untrusted input. A string under [KEY_SPEED] would
     * make Android's `getFloat` throw the first time the file is read — after
     * [importPrefs] has already cleared it — and a speed of zero or a repeat
     * mode outside Media3's three throws in the playback service. Known keys
     * therefore accept only their declared type; numeric values are clamped to
     * what the setter accepts, non-finite floats are refused, and enum-like
     * strings must name a rung this build knows.
     */
    private val KNOWN_PREFS: Map<String, KnownPref> = buildMap {
        fun bools(vararg keys: String) = keys.forEach {
            this[it] = KnownPref(PrefCarrier.BOOLEAN)
        }
        fun ints(vararg keys: String) = keys.forEach {
            this[it] = KnownPref(PrefCarrier.INT)
        }
        fun longs(vararg keys: String) = keys.forEach {
            this[it] = KnownPref(PrefCarrier.LONG)
        }
        fun floats(vararg keys: String) = keys.forEach {
            this[it] = KnownPref(PrefCarrier.FLOAT)
        }
        fun strings(vararg keys: String) = keys.forEach {
            this[it] = KnownPref(PrefCarrier.STRING)
        }
        fun enums(key: String, allowed: Set<String>) {
            this[key] = KnownPref(PrefCarrier.STRING, allowed)
        }
        fun stringSets(vararg keys: String) = keys.forEach {
            this[it] = KnownPref(PrefCarrier.STRING_SET)
        }

        bools(
            KEY_ONBOARDING_COMPLETE,
            KEY_WIFI_ONLY_DOWNLOADS,
            KEY_AUTO_REDOWNLOAD_MV_SOUND,
            KEY_AUTO_REPLACE_HQ_DOWNLOADS,
            KEY_AUTO_PREFETCH_HQ,
            KEY_EXPORT_DOWNLOADS,
            KEY_SMART_FADE,
            KEY_AUTOMIX_VIDEOS,
            KEY_SKIP_SILENCE,
            KEY_PRIVATE_LISTENING,
            KEY_PREFER_USB_DAC,
            KEY_LOUDNESS_NORMALIZATION,
            KEY_LOUDNESS_OFF_ON_SPEAKER,
            KEY_DOLBY_ATMOS,
            KEY_SPATIAL_AUDIO,
            KEY_EQ_ENABLED,
            KEY_EQ_FOCUSED,
            KEY_AUTOPLAY,
            KEY_AUTOPLAY_BEFORE_REPEAT_ALL,
            KEY_PERSONALISED_AUTOPLAY,
            KEY_VIDEO_RADIO,
            KEY_SHUFFLE_ENABLED,
            KEY_NOTIFICATION_PERMISSION_ASKED,
            KEY_NERD_STATS,
            KEY_REDUCE_ANIMATION,
            KEY_HIGH_PERFORMANCE_MODE,
            KEY_STOP_ON_TASK_REMOVED,
            KEY_HIDE_VOLUME_BAR,
            KEY_HIDE_STATUS_BAR_IN_PLAYER,
            KEY_HIDE_SONG_STATUS,
            KEY_WIDGET_OPENS_PLAYER,
            KEY_SHOW_QUEUE_HISTORY,
            KEY_SWIPE_TO_PLAY_NEXT,
            KEY_DONT_REPEAT_SUGGESTIONS,
            KEY_PREFER_MUSIC_ONLY,
            KEY_SKIP_NON_MUSIC_PARTS,
            KEY_SKIP_SPONSOR_PARTS,
            KEY_SMART_VERSION_ALIGNMENT,
            KEY_REDUCE_BLUR,
            KEY_LIQUID_GLASS,
            KEY_LYRICS_BLUR,
            KEY_ANIMATED_CANVAS,
            KEY_CANVAS_OVER_CELLULAR,
            KEY_SPOTIFY_CANVAS_AUTO_HIDE,
            KEY_PRIORITIZE_SPOTIFY_CANVAS,
            KEY_VIDEO_CANVAS,
            KEY_SIGNATURE_API_ENABLED,
            KEY_FULL_BLEED_ARTWORK,
            KEY_LEGACY_MESH_GRADIENT,
            KEY_SYNCED_LYRICS,
            KEY_PRIORITIZE_SYLLABLE_SYNC,
            KEY_GEMINI_TRANSLATION,
            KEY_PREFETCH_TRANSLATION,
            KEY_KEEP_DOWNLOAD_TRANSLATION,
            KEY_AUTO_DOWNLOAD_LIKED,
            KEY_AUTO_TRANSLATE_LYRICS,
            KEY_AUTO_LYRICS_TIMING,
            KEY_AI_LYRICS,
            KEY_AI_TIMING_AHEAD,
            KEY_SYLLABLE_DEFAULT_MIGRATED,
            KEY_LOCK_SCREEN_LYRICS,
            KEY_HIDDEN_EXPLORE_SEEDED_V1,
            KEY_SYNC_SAFETY_NEXT_PREP,
            KEY_SYNC_SAFETY_DOWNLOAD_RECHECK,
            KEY_SYNC_SAFETY_HQ_SYNC,
            KEY_SYNC_SAFETY_PER_LINE,
            KEY_RESUME_ON_HEADPHONES,
            KEY_REPLAY_GENRES,
            KEY_FILTER_NON_MUSIC_AUDIO,
            KEY_SHOW_CACHE_FOLDER,
            KEY_LASTFM_ENABLED,
            KEY_LASTFM_SCROBBLE_ENABLED,
            KEY_LASTFM_NOW_PLAYING,
            KEY_LASTFM_PRIMARY_ARTIST_ONLY,
            KEY_LISTENBRAINZ_ENABLED,
            KEY_LISTENBRAINZ_PRIMARY_ARTIST_ONLY,
            KEY_DISCORD_RPC_ENABLED,
            KEY_DISCORD_USE_DETAILS,
            KEY_DISCORD_SHOW_AUDIO_QUALITY,
            KEY_DISCORD_ADVANCED_MODE,
            KEY_DISCORD_BUTTON_1_VISIBLE,
            KEY_DISCORD_BUTTON_2_VISIBLE,
            KEY_DISCORD_INFO_DISMISSED,
            KEY_SYNC_SAFETY_NEXT_PREP,
            KEY_SYNC_SAFETY_DOWNLOAD_RECHECK,
            KEY_SYNC_SAFETY_HQ_SYNC,
            KEY_SYNC_SAFETY_PER_LINE,
        )
        ints(
            KEY_UPGRADE_LENGTH_SLACK_SECONDS,
            KEY_HQ_PREFETCH_LIMIT,
            KEY_CROSSFADE,
            KEY_EQ_TONE_X,
            KEY_EQ_TONE_Y,
            KEY_PITCH,
            KEY_REPEAT_MODE,
            KEY_PERFORMANCE_REFRESH_RATE,
            KEY_LYRICS_OFFSET_MS,
            KEY_PROXY_PORT,
            KEY_PROXY_BACKUP_PORT,
            KEY_SCROBBLE_MIN_DURATION,
            KEY_SCROBBLE_DELAY_SECONDS,
            KEY_LAST_VERSION_CODE,
        )
        longs(KEY_PRIVATE_LISTENING_SINCE, KEY_CACHE_LIMIT)
        floats(KEY_EQ_BALANCE, KEY_SPEED, KEY_SCROBBLE_DELAY_PERCENT)
        strings(
            KEY_DEEPGRAM_API_KEY,
            KEY_QUALITY_LEGACY,
            KEY_QUALITY_WIFI,
            KEY_QUALITY_CELLULAR,
            KEY_QUALITY_DOWNLOAD,
            KEY_AUTOMIX_PERFORMANCE_MODE,
            KEY_OUTPUT_PCM_MODE,
            KEY_EQ_MODE,
            KEY_EQ_BANDS,
            KEY_THEME,
            KEY_LOW_POWER_MODE,
            KEY_TRANSLATION_LANGUAGE,
            KEY_SIGNATURE_API_URL,
            KEY_LAST_PLAYER_SCREEN,
            KEY_LYRICS_SOURCES,
            KEY_LYRICS_SOURCES_SEEN,
            KEY_LYRICS_SOURCE_ORDER,
            KEY_PAXSENIX_API_KEY,
            KEY_GEMINI_API_KEY,
            KEY_CONTENT_COUNTRY,
            KEY_CHARTS_COUNTRY,
            KEY_LOCAL_MUSIC_SORT,
            KEY_DOWNLOADED_MUSIC_SORT,
            KEY_LIBRARY_SORT,
            KEY_DETAIL_SONG_SORTS,
            KEY_LOCAL_MUSIC_VIEW_TYPE,
            KEY_DOWNLOADED_MUSIC_VIEW_TYPE,
            KEY_HOME_RECENTS_VIEW_TYPE,
            KEY_LOCAL_MUSIC_FOLDER_URI,
            KEY_WEBDAV_URL,
            KEY_WEBDAV_USERNAME,
            KEY_SMB_HOST,
            KEY_SMB_SHARE,
            KEY_SMB_BASE_PATH,
            KEY_SMB_USERNAME,
            KEY_PROXY_MODE,
            KEY_PROXY_HOST,
            KEY_PROXY_BACKUP_HOST,
            KEY_PROXY_USERNAME,
            KEY_PROXY_SCOPE,
            KEY_PINNED_PLAYLISTS,
            KEY_LASTFM_USERNAME,
            KEY_LASTFM_SESSION_KEY,
            KEY_LASTFM_API_KEY,
            KEY_LASTFM_SECRET,
            KEY_LASTFM_ENDPOINT,
            KEY_LISTENBRAINZ_TOKEN,
            KEY_SPOTIFY_SPDC_TOKEN,
            KEY_DISCORD_USERNAME,
            KEY_DISCORD_NAME,
            KEY_DISCORD_AVATAR,
            KEY_DISCORD_STATUS,
            KEY_DISCORD_ACTIVITY_TYPE,
            KEY_DISCORD_ACTIVITY_NAME,
            KEY_DISCORD_BUTTON_1_TEXT,
            KEY_DISCORD_BUTTON_2_TEXT,
        )
        stringSets(KEY_COVER_CHANNELS, KEY_COVER_SINGERS, KEY_HIDDEN_EXPLORE_CATEGORIES)

        enums(KEY_QUALITY_WIFI, AudioQuality.entries.map { it.name }.toSet())
        enums(KEY_QUALITY_CELLULAR, AudioQuality.entries.map { it.name }.toSet())
        enums(KEY_QUALITY_DOWNLOAD, DownloadQuality.entries.map { it.name }.toSet())
        enums(KEY_AUTOMIX_PERFORMANCE_MODE, AutomixPerformanceMode.entries.map { it.name }.toSet())
        enums(KEY_OUTPUT_PCM_MODE, OutputPcmMode.entries.map { it.name }.toSet())
        enums(KEY_EQ_MODE, EqualizerMode.entries.map { it.name }.toSet())
        enums(KEY_THEME, ThemeMode.entries.map { it.name }.toSet())
        enums(KEY_LOW_POWER_MODE, LowPowerMode.entries.map { it.name }.toSet())
        enums(KEY_LAST_PLAYER_SCREEN, LastPlayerScreen.entries.map { it.name }.toSet())
        enums(KEY_PROXY_MODE, ProxyMode.entries.map { it.name }.toSet())
        enums(KEY_PROXY_SCOPE, ProxyScope.entries.map { it.name }.toSet())
        enums(KEY_LIBRARY_SORT, LibrarySort.entries.map { it.name }.toSet())
        enums(KEY_LOCAL_MUSIC_SORT, LocalMusicSort.entries.map { it.name }.toSet())
        enums(KEY_DOWNLOADED_MUSIC_SORT, LocalMusicSort.entries.map { it.name }.toSet())
        enums(KEY_LOCAL_MUSIC_VIEW_TYPE, LibraryViewType.entries.map { it.name }.toSet())
        enums(KEY_DOWNLOADED_MUSIC_VIEW_TYPE, LibraryViewType.entries.map { it.name }.toSet())
        enums(KEY_HOME_RECENTS_VIEW_TYPE, LibraryViewType.entries.map { it.name }.toSet())

        this[KEY_UPGRADE_LENGTH_SLACK_SECONDS] = KnownPref(
            PrefCarrier.INT,
            intRange = MIN_UPGRADE_LENGTH_SLACK_SECONDS..MAX_UPGRADE_LENGTH_SLACK_SECONDS,
        )
        this[KEY_HQ_PREFETCH_LIMIT] = KnownPref(PrefCarrier.INT, intRange = 0..10_000)
        this[KEY_EQ_TONE_X] = KnownPref(
            PrefCarrier.INT,
            intRange = -EqLayout.TONE_STEPS..EqLayout.TONE_STEPS,
        )
        this[KEY_EQ_TONE_Y] = KnownPref(
            PrefCarrier.INT,
            intRange = -EqLayout.TONE_STEPS..EqLayout.TONE_STEPS,
        )
        this[KEY_PITCH] = KnownPref(
            PrefCarrier.INT,
            intRange = PlaybackParametersMath.MIN_PITCH_SEMITONES..
                PlaybackParametersMath.MAX_PITCH_SEMITONES,
        )
        this[KEY_REPEAT_MODE] = KnownPref(
            PrefCarrier.INT,
            intRange = PlaybackParametersMath.MIN_REPEAT_MODE..
                PlaybackParametersMath.MAX_REPEAT_MODE,
        )
        this[KEY_LYRICS_OFFSET_MS] = KnownPref(
            PrefCarrier.INT,
            intRange = MIN_LYRICS_OFFSET_MS..MAX_LYRICS_OFFSET_MS,
        )
        this[KEY_PROXY_PORT] = KnownPref(PrefCarrier.INT, intRange = 0..65535)
        this[KEY_PROXY_BACKUP_PORT] = KnownPref(PrefCarrier.INT, intRange = 0..65535)
        this[KEY_EQ_BALANCE] = KnownPref(PrefCarrier.FLOAT, floatRange = -1f..1f)
        this[KEY_SPEED] = KnownPref(
            PrefCarrier.FLOAT,
            floatRange = PlaybackParametersMath.MIN_SPEED..PlaybackParametersMath.MAX_SPEED,
        )
        this[KEY_SCROBBLE_DELAY_PERCENT] = KnownPref(
            PrefCarrier.FLOAT,
            floatRange = 0.1f..1f,
        )
    }

    private fun sanitizeKnownPref(key: String, value: Any?): Any? {
        if (key == KEY_EQ_BANDS) return sanitizeEqualizerBands(value)
        val spec = KNOWN_PREFS[key] ?: return value
        return when (spec.carrier) {
            PrefCarrier.BOOLEAN -> value as? Boolean
            PrefCarrier.INT -> (value as? Int)?.let { raw ->
                spec.intRange?.let { raw.coerceIn(it) } ?: raw
            }
            PrefCarrier.LONG -> value as? Long
            PrefCarrier.FLOAT -> (value as? Float)
                ?.takeIf { it.isFinite() }
                ?.let { raw -> spec.floatRange?.let { raw.coerceIn(it) } ?: raw }
            PrefCarrier.STRING -> (value as? String)
                ?.takeIf { spec.allowed == null || it in spec.allowed }
            PrefCarrier.STRING_SET -> (value as? Set<*>)
                ?.filterIsInstance<String>()
                ?.toSet()
        }
    }

    private fun sanitizeEqualizerBands(value: Any?): Any? {
        val text = value as? String ?: return null
        val parts = text.split(',').map { it.trim() }
        if (parts.any { it.isEmpty() }) return null
        val parsed = parts.map { part ->
            part.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        }
        return parsed.joinToString(",") {
            KoAppSettings.clampBand(it).toString()
        }
    }

    /**
     * Drops or repairs values already in the file, before [readAll]'s typed
     * getters touch them. An import writes through [importPrefs], but a file
     * restored by an older build can still hold a string under a float key.
     */
    internal fun sanitizeStoredPreferences() {
        if (!AppSettings.prefsReady()) return
        val snapshot = prefs.all
        if (snapshot.isEmpty()) return
        val removals = mutableListOf<String>()
        val corrections = linkedMapOf<String, Any>()
        for ((key, value) in snapshot) {
            if (key !in KNOWN_PREFS) continue
            val normalized = sanitizeKnownPref(key, value)
            when {
                normalized == null -> removals += key
                normalized != value -> corrections[key] = normalized
            }
        }
        if (removals.isEmpty() && corrections.isEmpty()) return
        prefs.edit().apply {
            removals.forEach { remove(it) }
            corrections.forEach { (key, value) -> putSanitized(key, value) }
        }.apply()
    }

    private fun SharedPreferences.Editor.putSanitized(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> Unit
        }
    }

    // ── Settings transfer ───────────────────────────────────────────────

    /**
     * The only preferences [SettingsTransfer] may carry.
     *
     * Named one by one, with the type each must arrive as. A setting added to
     * this file next month is therefore not in a settings transfer until it is
     * listed here — the whitelist is the feature, not a shortcut around one.
     *
     * What is absent is as deliberate as what is present: account cookies,
     * listening history, likes, playlists, downloads, diagnostics, device-local
     * values, Spotify tokens and every user-supplied API key stay behind.
     */
    internal val TRANSFERABLE_KEYS: Map<String, TransferKey> = buildMap {
        val quality = AudioQuality.entries.map { it.name }.toSet()
        val downloadQuality = DownloadQuality.entries.map { it.name }.toSet()
        val automix = AutomixPerformanceMode.entries.map { it.name }.toSet()
        val lyrics = LyricsSource.entries.map { it.name }.toSet()
        val pcm = OutputPcmMode.entries.map { it.name }.toSet()
        val eqMode = EqualizerMode.entries.map { it.name }.toSet()
        val theme = ThemeMode.entries.map { it.name }.toSet()
        val lowPower = LowPowerMode.entries.map { it.name }.toSet()

        fun put(
            key: String,
            category: TransferCategory,
            type: TransferType,
            allowed: Set<String>? = null,
            floatRange: ClosedFloatingPointRange<Float>? = null,
            intRange: IntRange? = null,
        ) {
            this[key] = TransferKey(category, TransferSpec(type, allowed, floatRange, intRange))
        }

        // Audio quality and downloads.
        put(KEY_QUALITY_WIFI, TransferCategory.QUALITY, TransferType.STRING, quality)
        put(KEY_QUALITY_CELLULAR, TransferCategory.QUALITY, TransferType.STRING, quality)
        put(KEY_QUALITY_DOWNLOAD, TransferCategory.QUALITY, TransferType.STRING, downloadQuality)
        put(KEY_UPGRADE_LENGTH_SLACK_SECONDS, TransferCategory.QUALITY, TransferType.INT)
        put(KEY_WIFI_ONLY_DOWNLOADS, TransferCategory.QUALITY, TransferType.BOOLEAN)
        put(KEY_AUTO_REDOWNLOAD_MV_SOUND, TransferCategory.QUALITY, TransferType.BOOLEAN)
        put(KEY_AUTO_REPLACE_HQ_DOWNLOADS, TransferCategory.QUALITY, TransferType.BOOLEAN)
        put(KEY_AUTO_PREFETCH_HQ, TransferCategory.QUALITY, TransferType.BOOLEAN)
        put(KEY_HQ_PREFETCH_LIMIT, TransferCategory.QUALITY, TransferType.INT, intRange = 0..10_000)
        put(KEY_EXPORT_DOWNLOADS, TransferCategory.QUALITY, TransferType.BOOLEAN)

        // Automix and transitions.
        put(KEY_CROSSFADE, TransferCategory.AUTOMIX, TransferType.INT)
        put(KEY_SMART_FADE, TransferCategory.AUTOMIX, TransferType.BOOLEAN)
        put(KEY_AUTOMIX_VIDEOS, TransferCategory.AUTOMIX, TransferType.BOOLEAN)
        put(KEY_AUTOMIX_PERFORMANCE_MODE, TransferCategory.AUTOMIX, TransferType.STRING, automix)
        put(KEY_SKIP_SILENCE, TransferCategory.AUTOMIX, TransferType.BOOLEAN)
        put(KEY_SMART_VERSION_ALIGNMENT, TransferCategory.AUTOMIX, TransferType.BOOLEAN)

        // Lyrics: providers, order, translation and timing. No API keys.
        put(KEY_LYRICS_BLUR, TransferCategory.LYRICS, TransferType.BOOLEAN)
        put(KEY_LYRICS_OFFSET_MS, TransferCategory.LYRICS, TransferType.INT)
        put(KEY_TRANSLATION_LANGUAGE, TransferCategory.LYRICS, TransferType.STRING)
        put(KEY_SYNCED_LYRICS, TransferCategory.LYRICS, TransferType.BOOLEAN)
        put(KEY_LYRICS_SOURCES, TransferCategory.LYRICS, TransferType.STRING_LIST, lyrics)
        put(KEY_LYRICS_SOURCES_SEEN, TransferCategory.LYRICS, TransferType.STRING_LIST, lyrics)
        put(KEY_LYRICS_SOURCE_ORDER, TransferCategory.LYRICS, TransferType.STRING_LIST, lyrics)
        put(KEY_PRIORITIZE_SYLLABLE_SYNC, TransferCategory.LYRICS, TransferType.BOOLEAN)
        put(KEY_GEMINI_TRANSLATION, TransferCategory.LYRICS, TransferType.BOOLEAN)
        put(KEY_PREFETCH_TRANSLATION, TransferCategory.LYRICS, TransferType.BOOLEAN)
        put(KEY_KEEP_DOWNLOAD_TRANSLATION, TransferCategory.LYRICS, TransferType.BOOLEAN)
        put(KEY_AUTO_TRANSLATE_LYRICS, TransferCategory.LYRICS, TransferType.BOOLEAN)
        put(KEY_AUTO_LYRICS_TIMING, TransferCategory.LYRICS, TransferType.BOOLEAN)
        put(KEY_AI_LYRICS, TransferCategory.LYRICS, TransferType.BOOLEAN)
        put(KEY_AI_TIMING_AHEAD, TransferCategory.LYRICS, TransferType.BOOLEAN)
        put(KEY_LOCK_SCREEN_LYRICS, TransferCategory.LYRICS, TransferType.BOOLEAN)

        // Playback, including the equalizer and the audio pipeline.
        put(KEY_RESUME_ON_HEADPHONES, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_PERSONALISED_AUTOPLAY, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_AUTOPLAY, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_VIDEO_RADIO, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_PREFER_MUSIC_ONLY, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_SKIP_NON_MUSIC_PARTS, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_SKIP_SPONSOR_PARTS, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_OUTPUT_PCM_MODE, TransferCategory.PLAYBACK, TransferType.STRING, pcm)
        put(KEY_PREFER_USB_DAC, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_LOUDNESS_NORMALIZATION, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_LOUDNESS_OFF_ON_SPEAKER, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_DOLBY_ATMOS, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_SPATIAL_AUDIO, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_EQ_ENABLED, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_EQ_MODE, TransferCategory.PLAYBACK, TransferType.STRING, eqMode)
        put(KEY_EQ_TONE_X, TransferCategory.PLAYBACK, TransferType.INT)
        put(KEY_EQ_TONE_Y, TransferCategory.PLAYBACK, TransferType.INT)
        put(KEY_EQ_FOCUSED, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(
            KEY_EQ_BALANCE,
            TransferCategory.PLAYBACK,
            TransferType.FLOAT,
            floatRange = -1f..1f,
        )
        put(
            KEY_EQ_BANDS,
            TransferCategory.PLAYBACK,
            TransferType.FLOAT_LIST,
            floatRange = -EqLayout.MANUAL_RANGE_DB..EqLayout.MANUAL_RANGE_DB,
        )
        put(
            KEY_SPEED,
            TransferCategory.PLAYBACK,
            TransferType.FLOAT,
            floatRange = PlaybackParametersMath.MIN_SPEED..PlaybackParametersMath.MAX_SPEED,
        )
        put(KEY_PITCH, TransferCategory.PLAYBACK, TransferType.INT)
        put(KEY_SHUFFLE_ENABLED, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(
            KEY_REPEAT_MODE,
            TransferCategory.PLAYBACK,
            TransferType.INT,
            intRange = PlaybackParametersMath.MIN_REPEAT_MODE..PlaybackParametersMath.MAX_REPEAT_MODE,
        )
        put(KEY_DONT_REPEAT_SUGGESTIONS, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_SWIPE_TO_PLAY_NEXT, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_STOP_ON_TASK_REMOVED, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_SHOW_QUEUE_HISTORY, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_FILTER_NON_MUSIC_AUDIO, TransferCategory.PLAYBACK, TransferType.BOOLEAN)
        put(KEY_AUTO_DOWNLOAD_LIKED, TransferCategory.PLAYBACK, TransferType.BOOLEAN)

        // What the screen looks like and how the player is laid out.
        put(KEY_THEME, TransferCategory.DISPLAY, TransferType.STRING, theme)
        put(KEY_REDUCE_ANIMATION, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_LOW_POWER_MODE, TransferCategory.DISPLAY, TransferType.STRING, lowPower)
        put(KEY_REDUCE_BLUR, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_LIQUID_GLASS, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_FULL_BLEED_ARTWORK, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_LEGACY_MESH_GRADIENT, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_ANIMATED_CANVAS, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_CANVAS_OVER_CELLULAR, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_VIDEO_CANVAS, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_SPOTIFY_CANVAS_AUTO_HIDE, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_PRIORITIZE_SPOTIFY_CANVAS, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_HIGH_PERFORMANCE_MODE, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_PERFORMANCE_REFRESH_RATE, TransferCategory.DISPLAY, TransferType.INT)
        put(KEY_HIDE_VOLUME_BAR, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_HIDE_STATUS_BAR_IN_PLAYER, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_HIDE_SONG_STATUS, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_NERD_STATS, TransferCategory.DISPLAY, TransferType.BOOLEAN)
        put(KEY_WIDGET_OPENS_PLAYER, TransferCategory.DISPLAY, TransferType.BOOLEAN)
    }

    /**
     * The whitelisted preferences as they are on this device, for an export.
     *
     * Built from the live values rather than from the preference file, because
     * most settings are only written when they are first changed. A key still
     * sitting on its default is absent from the file, and exporting only what
     * is stored would let the receiving phone keep its own non-default value —
     * the opposite of "send my settings". Metadata with no flow (the lyrics
     * source list this build has shown) falls back to what is stored, or to
     * this build's offered list.
     */
    internal fun transferablePrefs(): Map<String, Any?> {
        if (!AppSettings.prefsReady()) return emptyMap()
        return TRANSFERABLE_KEYS.keys
            .associateWith { transferableValue(it) }
            .filterValues { it != null }
    }

    private fun transferableValue(key: String): Any? = when (key) {
        KEY_QUALITY_WIFI -> AppSettings.audioQualityWifi.value.name
        KEY_QUALITY_CELLULAR -> AppSettings.audioQualityCellular.value.name
        KEY_QUALITY_DOWNLOAD -> AppSettings.downloadQuality.value.name
        KEY_UPGRADE_LENGTH_SLACK_SECONDS -> AppSettings.upgradeLengthSlackSeconds.value
        KEY_WIFI_ONLY_DOWNLOADS -> AppSettings.wifiOnlyDownloads.value
        KEY_AUTO_REDOWNLOAD_MV_SOUND -> AppSettings.autoRedownloadMvSound.value
        KEY_AUTO_REPLACE_HQ_DOWNLOADS -> AppSettings.autoReplaceHqDownloads.value
        KEY_AUTO_PREFETCH_HQ -> AppSettings.autoPrefetchHq.value
        KEY_HQ_PREFETCH_LIMIT -> AppSettings.hqPrefetchLimit.value
        KEY_EXPORT_DOWNLOADS -> AppSettings.exportDownloads.value

        KEY_CROSSFADE -> AppSettings.crossfadeSeconds.value
        KEY_SMART_FADE -> AppSettings.smartFadeEnabled.value
        KEY_AUTOMIX_VIDEOS -> AppSettings.automixVideos.value
        KEY_AUTOMIX_PERFORMANCE_MODE -> AppSettings.automixPerformanceMode.value.name
        KEY_SKIP_SILENCE -> AppSettings.skipSilence.value
        KEY_SMART_VERSION_ALIGNMENT -> AppSettings.smartVersionAlignment.value

        KEY_LYRICS_BLUR -> AppSettings.lyricsBlur.value
        KEY_LYRICS_OFFSET_MS -> AppSettings.lyricsOffsetMs.value
        KEY_TRANSLATION_LANGUAGE -> AppSettings.translationLanguage.value
        KEY_SYNCED_LYRICS -> AppSettings.syncedLyrics.value
        KEY_LYRICS_SOURCES -> AppSettings.lyricsSources.value.joinToString(",") { it.name }
        KEY_LYRICS_SOURCES_SEEN -> prefs.getString(KEY_LYRICS_SOURCES_SEEN, null)
            ?.takeIf { it.isNotBlank() }
            ?: LyricsSource.offered.joinToString(",") { it.name }
        KEY_LYRICS_SOURCE_ORDER -> AppSettings.lyricsSourceOrder.value.joinToString(",") { it.name }
        KEY_PRIORITIZE_SYLLABLE_SYNC -> AppSettings.prioritizeSyllableSync.value
        KEY_GEMINI_TRANSLATION -> AppSettings.geminiTranslation.value
        KEY_PREFETCH_TRANSLATION -> AppSettings.prefetchTranslation.value
        KEY_KEEP_DOWNLOAD_TRANSLATION -> AppSettings.keepDownloadTranslation.value
        KEY_AUTO_TRANSLATE_LYRICS -> AppSettings.autoTranslateLyrics.value
        KEY_AUTO_LYRICS_TIMING -> AppSettings.autoLyricsTiming.value
        KEY_AI_LYRICS -> AppSettings.aiLyrics.value
        KEY_AI_TIMING_AHEAD -> AppSettings.aiTimingAhead.value
        KEY_LOCK_SCREEN_LYRICS -> AppSettings.lockScreenLyrics.value

        KEY_RESUME_ON_HEADPHONES -> AppSettings.resumeOnHeadphones.value
        KEY_PERSONALISED_AUTOPLAY -> AppSettings.personalisedAutoplay.value
        KEY_AUTOPLAY -> AppSettings.autoplay.value
        KEY_VIDEO_RADIO -> AppSettings.videoRadio.value
        KEY_PREFER_MUSIC_ONLY -> AppSettings.preferMusicOnly.value
        KEY_SKIP_NON_MUSIC_PARTS -> AppSettings.skipNonMusicParts.value
        KEY_SKIP_SPONSOR_PARTS -> AppSettings.skipSponsorParts.value
        KEY_OUTPUT_PCM_MODE -> AppSettings.outputPcmMode.value.name
        KEY_PREFER_USB_DAC -> AppSettings.preferUsbDac.value
        KEY_LOUDNESS_NORMALIZATION -> AppSettings.loudnessNormalization.value
        KEY_LOUDNESS_OFF_ON_SPEAKER -> AppSettings.loudnessOffOnSpeaker.value
        KEY_DOLBY_ATMOS -> AppSettings.dolbyAtmos.value
        KEY_SPATIAL_AUDIO -> AppSettings.spatialAudio.value
        KEY_EQ_ENABLED -> AppSettings.equalizerEnabled.value
        KEY_EQ_MODE -> AppSettings.equalizerMode.value.name
        KEY_EQ_TONE_X -> AppSettings.equalizerToneX.value
        KEY_EQ_TONE_Y -> AppSettings.equalizerToneY.value
        KEY_EQ_FOCUSED -> AppSettings.equalizerFocused.value
        KEY_EQ_BALANCE -> AppSettings.equalizerBalance.value
        KEY_EQ_BANDS -> AppSettings.equalizerBands.value.joinToString(",")
        KEY_SPEED -> AppSettings.playbackSpeed.value
        KEY_PITCH -> AppSettings.pitchSemitones.value
        KEY_SHUFFLE_ENABLED -> AppSettings.shuffleEnabled.value
        KEY_REPEAT_MODE -> AppSettings.repeatMode.value
        KEY_DONT_REPEAT_SUGGESTIONS -> AppSettings.dontRepeatSuggestions.value
        KEY_SWIPE_TO_PLAY_NEXT -> AppSettings.swipeToPlayNext.value
        KEY_STOP_ON_TASK_REMOVED -> AppSettings.stopOnTaskRemoved.value
        KEY_SHOW_QUEUE_HISTORY -> AppSettings.showQueueHistory.value
        KEY_FILTER_NON_MUSIC_AUDIO -> AppSettings.filterNonMusicAudio.value
        KEY_AUTO_DOWNLOAD_LIKED -> AppSettings.autoDownloadLiked.value

        KEY_THEME -> AppSettings.themeMode.value.name
        KEY_REDUCE_ANIMATION -> AppSettings.reduceAnimation.value
        KEY_LOW_POWER_MODE -> AppSettings.lowPowerMode.value.name
        KEY_REDUCE_BLUR -> AppSettings.reduceDynamicBlur.value
        KEY_LIQUID_GLASS -> AppSettings.liquidGlass.value
        KEY_FULL_BLEED_ARTWORK -> AppSettings.fullBleedArtwork.value
        KEY_LEGACY_MESH_GRADIENT -> AppSettings.legacyMeshGradient.value
        KEY_ANIMATED_CANVAS -> AppSettings.animatedCanvas.value
        KEY_CANVAS_OVER_CELLULAR -> AppSettings.canvasOverCellular.value
        KEY_VIDEO_CANVAS -> AppSettings.videoCanvas.value
        KEY_SPOTIFY_CANVAS_AUTO_HIDE -> AppSettings.spotifyCanvasAutoHide.value
        KEY_PRIORITIZE_SPOTIFY_CANVAS -> AppSettings.prioritizeSpotifyCanvas.value
        KEY_HIGH_PERFORMANCE_MODE -> AppSettings.highPerformanceMode.value
        KEY_PERFORMANCE_REFRESH_RATE -> AppSettings.performanceRefreshRate.value
        KEY_HIDE_VOLUME_BAR -> AppSettings.hideVolumeBar.value
        KEY_HIDE_STATUS_BAR_IN_PLAYER -> AppSettings.hideStatusBarInPlayer.value
        KEY_HIDE_SONG_STATUS -> AppSettings.hideSongStatus.value
        KEY_NERD_STATS -> AppSettings.showNerdStats.value
        KEY_WIDGET_OPENS_PLAYER -> AppSettings.widgetOpensPlayer.value

        else -> prefs.all[key]
    }

    /**
     * Writes only the keys in [values] that are on the whitelist and carry the
     * type they are declared with. Everything else is counted and skipped.
     *
     * A merge rather than [importPrefs]'s replace: this file says nothing about
     * history, accounts or downloads, so clearing the preference file would
     * quietly sign the listener out of services the transfer never mentioned.
     */
    internal fun applyTransferablePrefs(values: Map<String, Any?>): TransferApplyResult {
        if (!AppSettings.prefsReady()) return TransferApplyResult(0, values.size)
        val accepted = linkedMapOf<String, Any>()
        var skipped = 0
        for ((key, raw) in values) {
            val spec = TRANSFERABLE_KEYS[key]?.spec
            val normalized = spec?.normalize(raw)
            if (normalized == null) {
                skipped++
            } else {
                accepted[key] = normalized
            }
        }
        if (accepted.isNotEmpty()) {
            prefs.edit().apply {
                accepted.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.apply()
            AppSettings.reload()
        }
        return TransferApplyResult(accepted.size, skipped)
    }
}
