package com.music.bitchord.ko

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import com.music.bitchord.data.DebugLog as Debug
import com.music.bitchord.data.ExploreCategoryFilter
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.YouTubeProxy
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.LowPowerMode
import com.music.bitchord.data.settings.PrivateListening
import com.music.bitchord.data.settings.PrivateListeningSource
import com.music.bitchord.data.settings.lowPowerActive
import com.music.bitchord.playback.EqLayout
import com.music.bitchord.playback.PlaybackParametersMath
import com.music.bitchord.playback.SyncSafetyPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The fork's settings that used to live inside [AppSettings].
 *
 * Upstream owns the preference file, the flows and the network callback. These
 * are the switches, migrations and small helpers the Korean fork added on top;
 * [AppSettings] keeps one-line hooks so the next upstream merge does not have
 * to fight this block.
 */
internal object KoAppSettings {

    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** One line per setting change, for the exported diagnostic log. */
    private val settingsChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) {
                val prefs = AppSettings.prefs
                val value = runCatching { prefs.all[key] }.getOrNull()
                val shown = when (value) {
                    is Boolean -> if (key == "prefer_music_only") {
                        if (value) "on" else "off"
                    } else {
                        value.toString()
                    }
                    is Int, is Long, is Float -> value.toString()
                    is Set<*> -> "set(${value.size})"
                    null -> "cleared"
                    else -> if (value.toString().isBlank()) "cleared" else "set"
                }
                TrackLog.d(TAG, "setting: $key = $shown")
            }
        }

    fun installSettingsChangeListener() {
        val prefs = AppSettings.prefs
        listener?.let { runCatching { prefs.unregisterOnSharedPreferenceChangeListener(it) } }
        prefs.registerOnSharedPreferenceChangeListener(settingsChangeListener)
        listener = settingsChangeListener
    }

    /** See [AppSettings.init]: a key supplied with this build, used while none is saved. */
    private var bundledGeminiApiKey = ""

    fun prepareBundledKeys(context: Context) {
        // A Gemini key can be baked into a personal build's resources after it is
        // built (never committed). It is only a default: a key typed in Settings wins.
        bundledGeminiApiKey = context.getString(com.music.bitchord.R.string.default_gemini_api_key).trim()
        // Baked into this personal build after CI, like Gemini's — never committed.
        // Also only a default: a key typed in Settings (or removed there) wins.
        bundledDeepgramApiKey = context.getString(com.music.bitchord.R.string.default_deepgram_api_key).trim()
    }

    // ── Audio quality and downloads ────────────────────────────────────────

    val autoRedownloadMvSound = MutableStateFlow(true)
    val autoReplaceHqDownloads = MutableStateFlow(true)
    val autoPrefetchHq = MutableStateFlow(true)
    val hqPrefetchLimit = MutableStateFlow(500)

    fun readPreferMusicOnly(hadSettingsBeforeMigrations: Boolean) {
        val prefs = AppSettings.prefs
        val stored = if (prefs.contains(AppSettings.KEY_PREFER_MUSIC_ONLY)) {
            prefs.getBoolean(AppSettings.KEY_PREFER_MUSIC_ONLY, false)
        } else {
            null
        }
        AppSettings.preferMusicOnly.value = preferMusicOnlyDefault(
            stored,
            existingInstall = hadSettingsBeforeMigrations,
        )
        // A fresh install's true default has to be written, not just computed:
        // onboarding writes its flag in the same launch, so on the next start
        // the file is no longer empty and an absent key would otherwise be read
        // as an existing install and silently turn the feature back off.
        if (stored == null && !hadSettingsBeforeMigrations) {
            prefs.edit()
                .putBoolean(AppSettings.KEY_PREFER_MUSIC_ONLY, AppSettings.preferMusicOnly.value)
                .apply()
        }
    }

    fun readHqSettings() {
        val prefs = AppSettings.prefs
        autoRedownloadMvSound.value = prefs.getBoolean(KEY_AUTO_REDOWNLOAD_MV_SOUND, true)
        autoReplaceHqDownloads.value = prefs.getBoolean(KEY_AUTO_REPLACE_HQ_DOWNLOADS, true)
        autoPrefetchHq.value = prefs.getBoolean(KEY_AUTO_PREFETCH_HQ, true)
        hqPrefetchLimit.value = prefs.getInt(KEY_HQ_PREFETCH_LIMIT, 500).coerceIn(0, 10_000)
    }

    /**
     * Pins the cellular ceiling for installs that predate the High default.
     */
    fun migrateCellularQualityDefault() {
        val prefs = AppSettings.prefs
        if (prefs.contains(AppSettings.KEY_QUALITY_CELLULAR)) return
        val quality = cellularQualityDefault(existingInstall = prefs.all.isNotEmpty())
        prefs.edit().putString(AppSettings.KEY_QUALITY_CELLULAR, quality.name).apply()
    }

    /**
     * The automatic HQ pass is new. An install that updates into it never chose
     * to spend mobile data or storage on hundreds of background downloads, so
     * both switches start off there; a fresh install gets the feature on, and
     * its onboarding copy names what it does.
     */
    fun migrateHqAutomationDefaults(hadSettingsBeforeMigrations: Boolean) {
        val prefs = AppSettings.prefs
        val default = hqAutomationDefault(existingInstall = hadSettingsBeforeMigrations)
        val edit = prefs.edit()
        var changed = false
        if (!prefs.contains(KEY_AUTO_REPLACE_HQ_DOWNLOADS)) {
            edit.putBoolean(KEY_AUTO_REPLACE_HQ_DOWNLOADS, default)
            changed = true
        }
        if (!prefs.contains(KEY_AUTO_PREFETCH_HQ)) {
            edit.putBoolean(KEY_AUTO_PREFETCH_HQ, default)
            changed = true
        }
        if (changed) edit.apply()
    }

    fun setAutoRedownloadMvSound(value: Boolean) {
        autoRedownloadMvSound.value = value
        AppSettings.prefs.edit().putBoolean(KEY_AUTO_REDOWNLOAD_MV_SOUND, value).apply()
    }

    fun setAutoReplaceHqDownloads(value: Boolean) {
        autoReplaceHqDownloads.value = value
        AppSettings.prefs.edit().putBoolean(KEY_AUTO_REPLACE_HQ_DOWNLOADS, value).apply()
    }

    fun setAutoPrefetchHq(value: Boolean) {
        autoPrefetchHq.value = value
        AppSettings.prefs.edit().putBoolean(KEY_AUTO_PREFETCH_HQ, value).apply()
    }

    fun setHqPrefetchLimit(value: Int) {
        val normalized = value.coerceIn(0, 10_000)
        hqPrefetchLimit.value = normalized
        AppSettings.prefs.edit().putInt(KEY_HQ_PREFETCH_LIMIT, normalized).apply()
    }

    // ── Automix / playback extras ──────────────────────────────────────────

    val automixVideos = MutableStateFlow(true)
    val pitchSemitones = MutableStateFlow(0)
    val autoplayBeforeRepeatAll = MutableStateFlow<Boolean?>(null)
    val personalisedAutoplay = MutableStateFlow(true)
    val videoRadio = MutableStateFlow(true)
    val notificationPermissionAsked = MutableStateFlow(false)

    fun readAutomixVideos() {
        automixVideos.value = AppSettings.prefs.getBoolean(KEY_AUTOMIX_VIDEOS, true)
    }

    fun setAutomixVideos(value: Boolean) {
        automixVideos.value = value
        AppSettings.prefs.edit().putBoolean(KEY_AUTOMIX_VIDEOS, value).apply()
    }

    fun readPitchSemitones() {
        pitchSemitones.value = PlaybackParametersMath.clampPitchSemitones(
            AppSettings.prefs.getInt(KEY_PITCH, 0),
        )
    }

    fun setPitchSemitones(value: Int) {
        val clamped = PlaybackParametersMath.clampPitchSemitones(value)
        pitchSemitones.value = clamped
        AppSettings.prefs.edit().putInt(KEY_PITCH, clamped).apply()
    }

    fun readPlaybackExtras() {
        val prefs = AppSettings.prefs
        personalisedAutoplay.value = prefs.getBoolean(KEY_PERSONALISED_AUTOPLAY, true)
        videoRadio.value = prefs.getBoolean(KEY_VIDEO_RADIO, true)
        notificationPermissionAsked.value = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)
    }

    fun setPersonalisedAutoplay(value: Boolean) {
        personalisedAutoplay.value = value
        AppSettings.prefs.edit().putBoolean(KEY_PERSONALISED_AUTOPLAY, value).apply()
    }

    fun setVideoRadio(value: Boolean) {
        videoRadio.value = value
        AppSettings.prefs.edit().putBoolean(KEY_VIDEO_RADIO, value).apply()
    }

    /** Remembers that the notification permission has been asked for. */
    fun markNotificationPermissionAsked() {
        notificationPermissionAsked.value = true
        AppSettings.prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true).apply()
    }

    /**
     * Writes the AutoPlay switch and repeat-all's held value in one step.
     */
    fun applyAutoplayState(value: Boolean, beforeRepeatAll: Boolean?) {
        AppSettings.autoplay.value = value
        autoplayBeforeRepeatAll.value = beforeRepeatAll
        AppSettings.prefs.edit().apply {
            putBoolean(AppSettings.KEY_AUTOPLAY, value)
            if (beforeRepeatAll == null) {
                remove(KEY_AUTOPLAY_BEFORE_REPEAT_ALL)
            } else {
                putBoolean(KEY_AUTOPLAY_BEFORE_REPEAT_ALL, beforeRepeatAll)
            }
        }.apply()
    }

    /**
     * Keeps the persisted pair consistent after a restart or a backup import.
     */
    fun reconcileAutoplayWithRepeatAll() {
        val prefs = AppSettings.prefs
        val held = if (prefs.contains(KEY_AUTOPLAY_BEFORE_REPEAT_ALL)) {
            prefs.getBoolean(KEY_AUTOPLAY_BEFORE_REPEAT_ALL, false)
        } else {
            null
        }
        when {
            AppSettings.repeatMode.value == Player.REPEAT_MODE_ALL -> {
                applyAutoplayState(
                    value = false,
                    beforeRepeatAll = held ?: AppSettings.autoplay.value,
                )
            }
            held != null -> applyAutoplayState(value = held, beforeRepeatAll = null)
            else -> autoplayBeforeRepeatAll.value = null
        }
    }

    // ── Private listening ──────────────────────────────────────────────────

    val privateListening = MutableStateFlow(false)

    /** When the current private session began, or 0 while it is off. */
    private var privateListeningSinceMs: Long = 0L

    fun privateListeningSince(): Long = privateListeningSinceMs

    /**
     * Reads the switch and its clock, seeding the clock for an install that was
     * already private before this version stored one.
     */
    fun readPrivateListening() {
        val prefs = AppSettings.prefs
        val enabled = prefs.getBoolean(KEY_PRIVATE_LISTENING, false)
        val storedSince = prefs.getLong(KEY_PRIVATE_LISTENING_SINCE, 0L)
        if (!enabled) {
            privateListeningSinceMs = 0L
            privateListening.value = false
            if (prefs.contains(KEY_PRIVATE_LISTENING_SINCE)) {
                prefs.edit().remove(KEY_PRIVATE_LISTENING_SINCE).apply()
            }
            return
        }
        if (storedSince <= 0L) {
            privateListeningSinceMs = System.currentTimeMillis()
            prefs.edit().putLong(KEY_PRIVATE_LISTENING_SINCE, privateListeningSinceMs).apply()
            privateListening.value = true
            Debug.i(TAG, "private listening: on (since unknown, expires in 6h)")
        } else {
            privateListeningSinceMs = storedSince
            privateListening.value = true
        }
    }

    /**
     * Ends a private session that has run its six hours.
     *
     * Event-driven and cheap: called at process start (via readAll), when the
     * app returns to the foreground, and by ListeningRecorder before it decides
     * whether to record. No timer, alarm or poll exists behind it.
     */
    @Synchronized
    fun checkPrivateListeningExpiry(nowMs: Long = System.currentTimeMillis()) {
        if (!AppSettings.prefsReady()) return
        val prefs = AppSettings.prefs
        if (!privateListening.value) return
        if (privateListeningSinceMs <= 0L) {
            privateListeningSinceMs = nowMs
            prefs.edit().putLong(KEY_PRIVATE_LISTENING_SINCE, nowMs).apply()
            return
        }
        if (PrivateListening.isActive(true, privateListeningSinceMs, nowMs)) return
        privateListening.value = false
        privateListeningSinceMs = 0L
        prefs.edit()
            .putBoolean(KEY_PRIVATE_LISTENING, false)
            .remove(KEY_PRIVATE_LISTENING_SINCE)
            .apply()
        Debug.i(TAG, "private listening: off (by expired)")
    }

    /**
     * Switches private listening on or off.
     */
    @Synchronized
    fun setPrivateListening(
        value: Boolean,
        by: PrivateListeningSource = PrivateListeningSource.SETTINGS,
    ) {
        if (value == privateListening.value) return
        val now = System.currentTimeMillis()
        privateListeningSinceMs = if (value) now else 0L
        privateListening.value = value
        AppSettings.prefs.edit().apply {
            putBoolean(KEY_PRIVATE_LISTENING, value)
            if (value) putLong(KEY_PRIVATE_LISTENING_SINCE, now) else remove(KEY_PRIVATE_LISTENING_SINCE)
        }.apply()
        Debug.i(
            TAG,
            if (value) {
                "private listening: on (by ${by.logLabel})"
            } else {
                "private listening: off (by user)"
            },
        )
    }

    // ── Low power and onboarding ───────────────────────────────────────────

    val lowPowerMode = MutableStateFlow(LowPowerMode.AUTO)
    val lowPower = MutableStateFlow(false)
    val charging = MutableStateFlow(true)
    val heavyWorkAllowed: Boolean get() = !lowPower.value

    /** What the last battery broadcast said; kept to recompute [lowPower]. */
    private var powerSaveMode = false
    private var batteryPercent = 100

    fun batteryPercentValue(): Int = batteryPercent

    fun readLowPowerAndOnboarding(onboardingSaved: Boolean, existingInstall: Boolean) {
        val prefs = AppSettings.prefs
        lowPowerMode.value = runCatching {
            LowPowerMode.valueOf(
                prefs.getString(KEY_LOW_POWER_MODE, null) ?: LowPowerMode.AUTO.name,
            )
        }.getOrDefault(LowPowerMode.AUTO)
        lowPower.value = lowPowerActive(
            lowPowerMode.value,
            powerSaveMode,
            batteryPercent,
            charging.value,
        )
        // The welcome pages are new, so their flag is absent on an install that
        // already has a listener's settings, account or history. Sending those
        // through onboarding would read as the app having forgotten them, so
        // an existing install is marked done silently.
        val onboardingDone = onboardingSaved || existingInstall
        AppSettings.onboardingComplete.value = onboardingDone
        if (!onboardingSaved && onboardingDone) {
            prefs.edit().putBoolean(AppSettings.KEY_ONBOARDING_COMPLETE, true).apply()
        }
    }

    fun setLowPowerMode(value: LowPowerMode) {
        lowPowerMode.value = value
        AppSettings.prefs.edit().putString(KEY_LOW_POWER_MODE, value.name).apply()
        refreshLowPower()
    }

    /**
     * The device's side of [lowPower], fed by the broadcasts [LowPowerDevice]
     * listens for. Kept here so the shared player reads one source of truth.
     */
    fun setDevicePower(powerSave: Boolean, batteryPercent: Int, charging: Boolean) {
        this.powerSaveMode = powerSave
        this.batteryPercent = batteryPercent.coerceIn(0, 100)
        this.charging.value = charging
        refreshLowPower()
    }

    private fun refreshLowPower() {
        lowPower.value = lowPowerActive(
            lowPowerMode.value,
            powerSaveMode,
            batteryPercent,
            charging.value,
        )
    }

    /**
     * Whether this install holds anything a listener made before the welcome
     * pages existed: a saved setting, a restored session, or a recorded play.
     */
    fun hadDataBeforeOnboarding(): Boolean =
        AppSettings.prefs.all.keys.any { it != AppSettings.KEY_ONBOARDING_COMPLETE } ||
            runCatching { AppSettings.authStore.isSignedIn }.getOrDefault(false) ||
            runCatching {
                com.music.bitchord.data.stats.ListeningStats.hasStoredHistory(AppSettings.appContext)
            }.getOrDefault(false)

    // ── Sync safety ────────────────────────────────────────────────────────

    val syncSafetyNextPrep = MutableStateFlow(true)
    val syncSafetyDownloadRecheck = MutableStateFlow(false)
    val syncSafetyHqSync = MutableStateFlow(false)
    val syncSafetyPerLine = MutableStateFlow(true)

    /** The four switches above as the pure policy sees them. */
    val syncSafetySwitches: SyncSafetyPolicy.Switches
        get() = SyncSafetyPolicy.Switches(
            nextPrepLyrics = syncSafetyNextPrep.value,
            downloadedRecheck = syncSafetyDownloadRecheck.value,
            hqReapply = syncSafetyHqSync.value,
            perLineTiming = syncSafetyPerLine.value,
        )

    fun setSyncSafetyNextPrep(value: Boolean) {
        syncSafetyNextPrep.value = value
        AppSettings.prefs.edit().putBoolean(KEY_SYNC_SAFETY_NEXT_PREP, value).apply()
    }

    fun setSyncSafetyDownloadRecheck(value: Boolean) {
        syncSafetyDownloadRecheck.value = value
        AppSettings.prefs.edit().putBoolean(KEY_SYNC_SAFETY_DOWNLOAD_RECHECK, value).apply()
    }

    fun setSyncSafetyHqSync(value: Boolean) {
        syncSafetyHqSync.value = value
        AppSettings.prefs.edit().putBoolean(KEY_SYNC_SAFETY_HQ_SYNC, value).apply()
    }

    fun setSyncSafetyPerLine(value: Boolean) {
        syncSafetyPerLine.value = value
        AppSettings.prefs.edit().putBoolean(KEY_SYNC_SAFETY_PER_LINE, value).apply()
    }

    // ── Widgets ────────────────────────────────────────────────────────────

    val widgetOpensPlayer = MutableStateFlow(false)

    fun readWidgetOpensPlayerSetting() {
        widgetOpensPlayer.value = AppSettings.prefs.getBoolean(KEY_WIDGET_OPENS_PLAYER, false)
    }

    fun setWidgetOpensPlayer(value: Boolean) {
        widgetOpensPlayer.value = value
        AppSettings.prefs.edit().putBoolean(KEY_WIDGET_OPENS_PLAYER, value).apply()
        // The pending intent each widget already holds was built with the old
        // answer, so every placed instance has to be rebuilt now rather than
        // on the next track change.
        if (AppSettings.appContextReady()) {
            com.music.bitchord.widget.MediaWidget.refresh(AppSettings.appContext)
        }
    }

    /**
     * The widget-readable copy of [widgetOpensPlayer].
     */
    fun readWidgetOpensPlayer(context: Context): Boolean =
        if (AppSettings.prefsReady()) {
            widgetOpensPlayer.value
        } else {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_WIDGET_OPENS_PLAYER, false)
        }

    // ── Skipping and canvas ────────────────────────────────────────────────

    val skipNonMusicParts = MutableStateFlow(true)
    val skipSponsorParts = MutableStateFlow(false)
    val videoCanvas = MutableStateFlow(true)

    fun readSkipping() {
        val prefs = AppSettings.prefs
        skipNonMusicParts.value = prefs.getBoolean(KEY_SKIP_NON_MUSIC_PARTS, true)
        skipSponsorParts.value = prefs.getBoolean(KEY_SKIP_SPONSOR_PARTS, false)
    }

    fun setSkipNonMusicParts(value: Boolean) {
        skipNonMusicParts.value = value
        AppSettings.prefs.edit().putBoolean(KEY_SKIP_NON_MUSIC_PARTS, value).apply()
    }

    fun setSkipSponsorParts(value: Boolean) {
        skipSponsorParts.value = value
        AppSettings.prefs.edit().putBoolean(KEY_SKIP_SPONSOR_PARTS, value).apply()
    }

    fun readVideoCanvas() {
        videoCanvas.value = AppSettings.prefs.getBoolean(KEY_VIDEO_CANVAS, true)
    }

    fun setVideoCanvas(value: Boolean) {
        videoCanvas.value = value
        AppSettings.prefs.edit().putBoolean(KEY_VIDEO_CANVAS, value).apply()
    }

    // ── Lyrics, translation and AI ─────────────────────────────────────────

    val geminiTranslation = MutableStateFlow(false)
    val geminiApiKey = MutableStateFlow("")
    val prefetchTranslation = MutableStateFlow(true)
    val keepDownloadTranslation = MutableStateFlow(true)
    val autoTranslateLyrics = MutableStateFlow(false)
    val autoLyricsTiming = MutableStateFlow(false)
    val aiLyrics = MutableStateFlow(true)
    val aiTimingAhead = MutableStateFlow(true)
    val autoDownloadLiked = MutableStateFlow(true)
    val lockScreenLyrics = MutableStateFlow(false)

    /** Deepgram's key for AI lyric timing; blank turns Deepgram (and Whisper) off. */
    val deepgramApiKey = MutableStateFlow("")
    private var bundledDeepgramApiKey = ""

    /** Whether the key in use is the one this build came with (vs. typed in Settings). */
    val deepgramKeyIsBundled: Boolean
        get() = bundledDeepgramApiKey.isNotBlank() && deepgramApiKey.value == bundledDeepgramApiKey

    fun readBundledKeys() {
        deepgramApiKey.value = AppSettings.authStore.secret(KEY_DEEPGRAM_API_KEY) ?: bundledDeepgramApiKey
        com.music.bitchord.data.lyrics.Deepgram.apiKey = deepgramApiKey.value
    }

    fun readLyricsAndAiSettings() {
        val prefs = AppSettings.prefs
        val authStore = AppSettings.authStore
        // This fork wants the word-by-word sweep wherever any source has one,
        // so the switch starts on — and is turned on once for an install that
        // had saved it off before the fork changed the default.
        if (!prefs.getBoolean(KEY_SYLLABLE_DEFAULT_MIGRATED, false)) {
            prefs.edit()
                .putBoolean(AppSettings.KEY_PRIORITIZE_SYLLABLE_SYNC, true)
                .putBoolean(KEY_SYLLABLE_DEFAULT_MIGRATED, true)
                .apply()
        }
        AppSettings.prioritizeSyllableSync.value =
            prefs.getBoolean(AppSettings.KEY_PRIORITIZE_SYLLABLE_SYNC, true)
        autoTranslateLyrics.value = prefs.getBoolean(KEY_AUTO_TRANSLATE_LYRICS, false)
        autoLyricsTiming.value = prefs.getBoolean(KEY_AUTO_LYRICS_TIMING, false)
        aiLyrics.value = prefs.getBoolean(KEY_AI_LYRICS, true)
        aiTimingAhead.value = prefs.getBoolean(KEY_AI_TIMING_AHEAD, true)
        syncSafetyNextPrep.value = prefs.getBoolean(KEY_SYNC_SAFETY_NEXT_PREP, true)
        syncSafetyDownloadRecheck.value = prefs.getBoolean(KEY_SYNC_SAFETY_DOWNLOAD_RECHECK, false)
        syncSafetyHqSync.value = prefs.getBoolean(KEY_SYNC_SAFETY_HQ_SYNC, false)
        syncSafetyPerLine.value = prefs.getBoolean(KEY_SYNC_SAFETY_PER_LINE, true)
        AppSettings.paxSenixApiKey.value = authStore.secret(AppSettings.KEY_PAXSENIX_API_KEY).orEmpty()
        com.music.bitchord.data.lyrics.PaxSenix.setApiKey(AppSettings.paxSenixApiKey.value)
        geminiApiKey.value = authStore.secret(KEY_GEMINI_API_KEY) ?: bundledGeminiApiKey
        geminiTranslation.value = prefs.getBoolean(KEY_GEMINI_TRANSLATION, bundledGeminiApiKey.isNotBlank())
        com.music.bitchord.data.lyrics.LyricsTranslation.geminiApiKey = geminiApiKey.value
        com.music.bitchord.data.lyrics.LyricsTranslation.geminiEnabled = geminiTranslation.value
        prefetchTranslation.value = prefs.getBoolean(KEY_PREFETCH_TRANSLATION, true)
        keepDownloadTranslation.value = prefs.getBoolean(KEY_KEEP_DOWNLOAD_TRANSLATION, true)
        autoDownloadLiked.value = prefs.getBoolean(KEY_AUTO_DOWNLOAD_LIKED, true)
        lockScreenLyrics.value = prefs.getBoolean(KEY_LOCK_SCREEN_LYRICS, false)
    }

    fun setGeminiTranslation(value: Boolean) {
        geminiTranslation.value = value
        AppSettings.prefs.edit().putBoolean(KEY_GEMINI_TRANSLATION, value).apply()
        com.music.bitchord.data.lyrics.LyricsTranslation.geminiEnabled = value
    }

    fun setPrefetchTranslation(value: Boolean) {
        prefetchTranslation.value = value
        AppSettings.prefs.edit().putBoolean(KEY_PREFETCH_TRANSLATION, value).apply()
    }

    fun setKeepDownloadTranslation(value: Boolean) {
        keepDownloadTranslation.value = value
        AppSettings.prefs.edit().putBoolean(KEY_KEEP_DOWNLOAD_TRANSLATION, value).apply()
    }

    fun setAutoTranslateLyrics(value: Boolean) {
        autoTranslateLyrics.value = value
        AppSettings.prefs.edit().putBoolean(KEY_AUTO_TRANSLATE_LYRICS, value).apply()
    }

    fun setAutoLyricsTiming(value: Boolean) {
        autoLyricsTiming.value = value
        AppSettings.prefs.edit().putBoolean(KEY_AUTO_LYRICS_TIMING, value).apply()
    }

    fun setAiTimingAhead(value: Boolean) {
        aiTimingAhead.value = value
        AppSettings.prefs.edit().putBoolean(KEY_AI_TIMING_AHEAD, value).apply()
    }

    fun setAiLyrics(value: Boolean) {
        aiLyrics.value = value
        AppSettings.prefs.edit().putBoolean(KEY_AI_LYRICS, value).apply()
    }

    fun setAutoDownloadLiked(value: Boolean) {
        autoDownloadLiked.value = value
        AppSettings.prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_LIKED, value).apply()
    }

    fun setLockScreenLyrics(value: Boolean) {
        lockScreenLyrics.value = value
        AppSettings.prefs.edit().putBoolean(KEY_LOCK_SCREEN_LYRICS, value).apply()
    }

    fun setDeepgramApiKey(value: String) {
        val normalized = value.trim()
        deepgramApiKey.value = normalized
        putSecret(KEY_DEEPGRAM_API_KEY, normalized)
        com.music.bitchord.data.lyrics.Deepgram.apiKey = normalized
    }

    fun setGeminiApiKey(value: String) {
        val normalized = value.trim()
        geminiApiKey.value = normalized
        putSecret(KEY_GEMINI_API_KEY, normalized)
        com.music.bitchord.data.lyrics.LyricsTranslation.geminiApiKey = normalized
    }

    // ── Discovery ──────────────────────────────────────────────────────────

    val contentCountry = MutableStateFlow(DEFAULT_CONTENT_COUNTRY)
    val chartsCountry = MutableStateFlow(DEFAULT_CONTENT_COUNTRY)
    val coverChannels = MutableStateFlow<Set<String>>(emptySet())
    val coverSingers = MutableStateFlow<Set<String>>(emptySet())
    val hiddenExploreCategories = MutableStateFlow<Set<String>>(emptySet())
    val resumeOnHeadphones = MutableStateFlow(true)

    fun readDiscoverySettings() {
        val prefs = AppSettings.prefs
        contentCountry.value = prefs.getString(KEY_CONTENT_COUNTRY, null)?.takeIf { it.length == 2 }
            ?: DEFAULT_CONTENT_COUNTRY
        chartsCountry.value = prefs.getString(KEY_CHARTS_COUNTRY, null)?.takeIf { it.isNotBlank() }
            ?: contentCountry.value
        coverChannels.value = prefs.getStringSet(KEY_COVER_CHANNELS, null)?.toSet().orEmpty()
        coverSingers.value = prefs.getStringSet(KEY_COVER_SINGERS, null)?.toSet().orEmpty()
        val hiddenSeeded = prefs.getBoolean(KEY_HIDDEN_EXPLORE_SEEDED_V1, false)
        val storedHidden = prefs.getStringSet(KEY_HIDDEN_EXPLORE_CATEGORIES, null)?.toSet().orEmpty()
        hiddenExploreCategories.value = ExploreCategoryFilter.seedHidden(storedHidden, hiddenSeeded)
        if (!hiddenSeeded) {
            prefs.edit()
                .putStringSet(KEY_HIDDEN_EXPLORE_CATEGORIES, HashSet(hiddenExploreCategories.value))
                .putBoolean(KEY_HIDDEN_EXPLORE_SEEDED_V1, true)
                .apply()
        }
        resumeOnHeadphones.value = prefs.getBoolean(KEY_RESUME_ON_HEADPHONES, true)
    }

    fun setContentCountry(value: String) {
        val code = value.trim().uppercase(java.util.Locale.ROOT).takeIf { it.length == 2 } ?: return
        contentCountry.value = code
        // The charts follow along: a new content country is a new "here".
        chartsCountry.value = code
        AppSettings.prefs.edit()
            .putString(KEY_CONTENT_COUNTRY, code)
            .putString(KEY_CHARTS_COUNTRY, code)
            .apply()
    }

    fun setChartsCountry(value: String) {
        val code = value.trim().takeIf { it.isNotEmpty() } ?: return
        chartsCountry.value = code
        AppSettings.prefs.edit().putString(KEY_CHARTS_COUNTRY, code).apply()
    }

    fun setCoverChannel(channelId: String, cover: Boolean) {
        if (channelId.isBlank()) return
        val next = if (cover) coverChannels.value + channelId else coverChannels.value - channelId
        if (next == coverChannels.value) return
        coverChannels.value = next
        AppSettings.prefs.edit().putStringSet(KEY_COVER_CHANNELS, next).apply()
    }

    /** Called for every cover title read; writes only when [name] is new. */
    fun rememberCoverSinger(name: String) {
        val singer = name.trim()
        if (singer.length < 2 || singer.length > 40 || singer in coverSingers.value) return
        if (!AppSettings.prefsReady()) return
        var next: Set<String> = emptySet()
        coverSingers.update { current ->
            next = if (singer in current) current else (current + singer).let { all ->
                if (all.size > MAX_COVER_SINGERS) all.drop(all.size - MAX_COVER_SINGERS).toSet() else all
            }
            next
        }
        AppSettings.prefs.edit().putStringSet(KEY_COVER_SINGERS, HashSet(next)).apply()
    }

    /** Hides one Explore category by the label the listener long-pressed. */
    fun hideExploreCategory(title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        setHiddenExploreCategories(hiddenExploreCategories.value + trimmed)
    }

    /** Brings one hidden Explore category back into the grid. */
    fun showExploreCategory(title: String) {
        setHiddenExploreCategories(hiddenExploreCategories.value - title)
    }

    /** Brings every Explore category back. The one-time seed flag stays set. */
    fun clearHiddenExploreCategories() {
        setHiddenExploreCategories(emptySet())
    }

    private fun setHiddenExploreCategories(next: Set<String>) {
        val stable = next.toSet()
        hiddenExploreCategories.value = stable
        if (!AppSettings.prefsReady()) return
        AppSettings.prefs.edit()
            .putStringSet(KEY_HIDDEN_EXPLORE_CATEGORIES, HashSet(stable))
            .apply()
    }

    fun setResumeOnHeadphones(value: Boolean) {
        resumeOnHeadphones.value = value
        AppSettings.prefs.edit().putBoolean(KEY_RESUME_ON_HEADPHONES, value).apply()
    }

    // ── Network proxy ──────────────────────────────────────────────────────

    val proxyMode = MutableStateFlow(ProxyMode.OFF)
    val proxyHost = MutableStateFlow("")
    val proxyPort = MutableStateFlow(0)
    val proxyBackupHost = MutableStateFlow("")
    val proxyBackupPort = MutableStateFlow(0)
    val proxyUsername = MutableStateFlow("")
    val proxyPassword = MutableStateFlow("")
    val proxyScope = MutableStateFlow(ProxyScope.MUSIC_ONLY)
    val proxyRevision = MutableStateFlow(0L)

    /**
     * Loads only the saved proxy route, synchronously and without the rest of
     * the settings.
     */
    fun initProxy(context: Context, authStore: com.music.bitchord.auth.AuthStore) {
        AppSettings.appContext = context.applicationContext
        AppSettings.prefs = AppSettings.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        AppSettings.authStore = authStore
        readProxy()
    }

    /**
     * Saves the proxy in one write.
     */
    fun setProxyConfig(config: ProxyConfig) {
        val prefs = AppSettings.prefs
        val normalized = config.normalized()
        val port = normalized.port.coerceIn(0, 65535)
        val backupPort = normalized.backupPort.coerceIn(0, 65535)
        proxyMode.value = normalized.mode
        proxyHost.value = normalized.host
        proxyPort.value = port
        proxyBackupHost.value = normalized.backupHost
        proxyBackupPort.value = backupPort
        proxyUsername.value = normalized.username
        proxyPassword.value = normalized.password
        proxyScope.value = normalized.scope
        prefs.edit()
            .putString(KEY_PROXY_MODE, normalized.mode.name)
            .putString(KEY_PROXY_HOST, normalized.host)
            .putInt(KEY_PROXY_PORT, port)
            .putString(KEY_PROXY_BACKUP_HOST, normalized.backupHost)
            .putInt(KEY_PROXY_BACKUP_PORT, backupPort)
            .putString(KEY_PROXY_USERNAME, normalized.username)
            .putString(KEY_PROXY_SCOPE, normalized.scope.name)
            .apply()
        AppSettings.authStore.proxyPassword = normalized.password.ifEmpty { null }
        publishProxy()
    }

    /** Moves the proxy between Music-only and all YouTube traffic. */
    fun setProxyScope(value: ProxyScope) {
        proxyScope.value = value
        AppSettings.prefs.edit().putString(KEY_PROXY_SCOPE, value.name).apply()
        publishProxy()
    }

    /** Reads the proxy fields and publishes them; shared by init and initProxy. */
    fun readProxy() {
        val prefs = AppSettings.prefs
        proxyMode.value = runCatching {
            ProxyMode.valueOf(prefs.getString(KEY_PROXY_MODE, null) ?: ProxyMode.OFF.name)
        }.getOrDefault(ProxyMode.OFF)
        proxyHost.value = prefs.getString(KEY_PROXY_HOST, "").orEmpty()
        proxyPort.value = prefs.getInt(KEY_PROXY_PORT, 0)
        proxyBackupHost.value = prefs.getString(KEY_PROXY_BACKUP_HOST, "").orEmpty()
        proxyBackupPort.value = prefs.getInt(KEY_PROXY_BACKUP_PORT, 0)
        proxyUsername.value = prefs.getString(KEY_PROXY_USERNAME, "").orEmpty()
        proxyPassword.value = AppSettings.authStore.proxyPassword.orEmpty()
        proxyScope.value = runCatching {
            ProxyScope.valueOf(prefs.getString(KEY_PROXY_SCOPE, null) ?: ProxyScope.MUSIC_ONLY.name)
        }.getOrDefault(ProxyScope.MUSIC_ONLY)
        publishProxy()
    }

    private fun publishProxy() {
        if (!AppSettings.prefsReady()) return
        if (!AppSettings.authReady()) return
        val next = ProxyConfig(
            mode = proxyMode.value,
            host = proxyHost.value,
            port = proxyPort.value,
            backupHost = proxyBackupHost.value,
            backupPort = proxyBackupPort.value,
            username = proxyUsername.value,
            password = proxyPassword.value,
            scope = proxyScope.value,
        ).normalized()
        val changed = next != YouTubeProxy.config
        YouTubeProxy.update(next)
        if (!changed) return
        // The route the stored verdicts were made on has moved. Start every
        // identity from UNKNOWN so the next Home/Explore request re-checks
        // through the new route instead of trusting yesterday's answer.
        MusicGateFallback.resetAll()
        proxyRevision.value += 1
    }

    /** The proxy as it stands, credentials included, for SettingsTransfer. */
    fun currentProxyConfig(): ProxyConfig = ProxyConfig(
        mode = proxyMode.value,
        host = proxyHost.value,
        port = proxyPort.value,
        backupHost = proxyBackupHost.value,
        backupPort = proxyBackupPort.value,
        username = proxyUsername.value,
        password = proxyPassword.value,
        scope = proxyScope.value,
    ).normalized()

    // ── Secrets ────────────────────────────────────────────────────────────

    /**
     * Writes one credential to the encrypted store, not the plain settings
     * file. [AppSettings.authStore] is absent only in a unit test that installs
     * a preference file directly; there the old plain write keeps the test
     * fixture meaningful and never runs on a device.
     */
    fun putSecret(key: String, value: String) {
        if (AppSettings.authReady()) {
            AppSettings.authStore.setSecret(key, value)
        } else {
            AppSettings.prefs.edit().putString(key, value).apply()
        }
    }

    /**
     * Moves credentials an older build left in [PREFS_NAME] into [AuthStore].
     */
    fun migrateSecretsToAuthStore() {
        if (!AppSettings.prefsReady()) return
        if (!AppSettings.authReady()) return
        val prfs = AppSettings.prefs
        val present = AppSettings.SECRETS.filter { prfs.contains(it) }
        if (present.isEmpty()) return
        present.forEach { key ->
            (prfs.all[key] as? String)?.takeIf { it.isNotEmpty() }?.let { value ->
                AppSettings.authStore.setSecret(key, value)
            }
        }
        prfs.edit().apply {
            present.forEach { remove(it) }
        }.apply()
    }

    // ── Pure helpers ───────────────────────────────────────────────────────

    fun clampBalance(value: Float): Float =
        value.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f

    fun clampScrobbleDelay(value: Float): Float =
        value.takeIf { it.isFinite() }?.coerceIn(0.1f, 1f) ?: 0.5f

    fun clampBand(value: Float): Float =
        value.takeIf { it.isFinite() }
            ?.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
            ?: 0f
}
