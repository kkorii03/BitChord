package com.music.bitchord.ko

import com.music.bitchord.data.settings.AudioQuality

/** Shared with the widgets' fallback read, so both halves name the same file. */
internal const val PREFS_NAME = "bitchord_settings"

/** Diagnostic tag shared with the rest of the playback trail. */
internal const val TAG = "BitChord"

internal const val KEY_AUTO_REDOWNLOAD_MV_SOUND = "auto_redownload_mv_sound"
internal const val KEY_AUTO_REPLACE_HQ_DOWNLOADS = "auto_replace_hq_downloads"
internal const val KEY_AUTO_PREFETCH_HQ = "auto_prefetch_hq"
internal const val KEY_HQ_PREFETCH_LIMIT = "hq_prefetch_limit"
internal const val KEY_AUTOMIX_VIDEOS = "automix_videos"
internal const val KEY_PRIVATE_LISTENING = "private_listening"
internal const val KEY_PRIVATE_LISTENING_SINCE = "private_listening_since"
internal const val KEY_PITCH = "playback_pitch_semitones"
internal const val KEY_AUTOPLAY_BEFORE_REPEAT_ALL = "autoplay_before_repeat_all"
internal const val KEY_PERSONALISED_AUTOPLAY = "personalised_autoplay"
internal const val KEY_VIDEO_RADIO = "video_radio"
internal const val KEY_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
internal const val KEY_LOW_POWER_MODE = "low_power_mode"
internal const val KEY_WIDGET_OPENS_PLAYER = "widget_opens_player"
internal const val KEY_SKIP_NON_MUSIC_PARTS = "skip_non_music_parts"
internal const val KEY_SKIP_SPONSOR_PARTS = "skip_sponsor_parts"
internal const val KEY_VIDEO_CANVAS = "ko_video_canvas"
internal const val KEY_GEMINI_API_KEY = "gemini_api_key"
internal const val KEY_GEMINI_TRANSLATION = "gemini_translation"
internal const val KEY_PREFETCH_TRANSLATION = "prefetch_translation"
internal const val KEY_KEEP_DOWNLOAD_TRANSLATION = "keep_download_translation"
internal const val KEY_AUTO_DOWNLOAD_LIKED = "auto_download_liked"
internal const val KEY_AUTO_TRANSLATE_LYRICS = "auto_translate_lyrics"
internal const val KEY_AUTO_LYRICS_TIMING = "auto_lyrics_timing"
internal const val KEY_AI_LYRICS = "ko_ai_lyrics"
internal const val KEY_AI_TIMING_AHEAD = "ko_ai_timing_ahead"
internal const val KEY_SYNC_SAFETY_NEXT_PREP = "ko_sync_safety_next_prep"
internal const val KEY_SYNC_SAFETY_DOWNLOAD_RECHECK = "ko_sync_safety_download_recheck"
internal const val KEY_SYNC_SAFETY_HQ_SYNC = "ko_sync_safety_hq_sync"
internal const val KEY_SYLLABLE_DEFAULT_MIGRATED = "ko_syllable_default_migrated"
internal const val KEY_LOCK_SCREEN_LYRICS = "lock_screen_lyrics"
internal const val KEY_CONTENT_COUNTRY = "ko_content_country"
internal const val KEY_CHARTS_COUNTRY = "ko_charts_country"
internal const val KEY_COVER_CHANNELS = "ko_cover_channels"
internal const val KEY_COVER_SINGERS = "ko_cover_singers"
internal const val KEY_HIDDEN_EXPLORE_CATEGORIES = "ko_hidden_explore_categories"
internal const val KEY_HIDDEN_EXPLORE_SEEDED_V1 = "hidden_explore_seeded_v1"
internal const val MAX_COVER_SINGERS = 400
internal const val KEY_RESUME_ON_HEADPHONES = "ko_resume_on_headphones"
internal const val DEFAULT_CONTENT_COUNTRY = "KR"
internal const val KEY_PROXY_MODE = "proxy_mode"
internal const val KEY_PROXY_HOST = "proxy_host"
internal const val KEY_PROXY_PORT = "proxy_port"
internal const val KEY_PROXY_BACKUP_HOST = "proxy_backup_host"
internal const val KEY_PROXY_BACKUP_PORT = "proxy_backup_port"
internal const val KEY_PROXY_USERNAME = "proxy_username"
internal const val KEY_PROXY_SCOPE = "proxy_scope"
internal const val KEY_DEEPGRAM_API_KEY = "deepgram_api_key"

/**
 * The cellular ceiling an install gets when it has never stored one.
 *
 * The old default was Lossless and was only written down when the listener
 * changed it, so "no stored key" means either a fresh install (which gets the
 * new [AudioQuality.HIGH] default) or an existing install that has always been
 * on Lossless (which keeps it). Whether any settings at all have been written
 * tells the two apart: the migration is the only caller and runs before this
 * launch writes anything.
 */
internal fun cellularQualityDefault(existingInstall: Boolean): AudioQuality =
    if (existingInstall) AudioQuality.LOSSLESS else AudioQuality.HIGH

/**
 * Off for an install that updated into the feature; on for a fresh one.
 *
 * [existingInstall] is whether the preference file already held settings before
 * this launch wrote any — not whether onboarding is complete. A normal app
 * update has finished onboarding, so the onboarding flag cannot stand in for
 * this.
 */
internal fun hqAutomationDefault(existingInstall: Boolean): Boolean = !existingInstall

/**
 * The music-only preference when this launch has never stored one.
 *
 * The catalogue song release is what the app is for, so a genuinely fresh
 * install (no settings written before this launch) starts on. An install that
 * already had settings was running with the old default, which was off, so an
 * absent key stays off rather than silently flipping that listener's playback.
 * A stored [Boolean] — including the `false` a listener chose — is kept exactly
 * as written; only the absence is defaulted.
 *
 * [existingInstall] is whether the preference file already held settings before
 * this launch wrote any — not whether onboarding is complete. A normal app
 * update has finished onboarding, so the onboarding flag cannot stand in for
 * this.
 */
internal fun preferMusicOnlyDefault(stored: Boolean?, existingInstall: Boolean): Boolean =
    stored ?: !existingInstall
