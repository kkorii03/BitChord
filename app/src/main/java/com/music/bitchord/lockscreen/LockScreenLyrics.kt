package com.music.bitchord.lockscreen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The lyrics the playback service loaded for the track it is playing, for the
 * lock screen to draw without a lookup of its own.
 */
object NowPlayingLyrics {
    data class Entry(val videoId: String, val lines: List<LyricLine>)

    private val _current = MutableStateFlow<Entry?>(null)
    val current: StateFlow<Entry?> = _current.asStateFlow()

    fun publish(videoId: String?, lines: List<LyricLine>?) {
        _current.value = if (videoId == null || lines.isNullOrEmpty()) null else Entry(videoId, lines)
    }
}

/**
 * Puts [LockScreenLyricsActivity] over the lock screen while something is
 * loaded in the player — playing or paused.
 *
 * Started as the screen goes off, so it is already there when the screen comes
 * back on rather than appearing a moment after the lock screen; and checked
 * again as the screen comes on, for the times the first start was refused.
 *
 * Starting an activity from the background needs "Display over other apps".
 * Without it, nothing happens: the setting row says so and offers the switch.
 */
object LockScreenLyrics {
    private const val TAG = "BitChord"

    private var receiver: BroadcastReceiver? = null

    /** Whether "Display over other apps" is granted — what the start needs. */
    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Called by the playback service as it starts; [hasTrack] reads its player. */
    fun attach(context: Context, hasTrack: () -> Boolean) {
        if (receiver != null) return
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> maybeShow(context, hasTrack(), requireLocked = false)
                    Intent.ACTION_SCREEN_ON -> if (!LockScreenLyricsActivity.showing) {
                        maybeShow(context, hasTrack(), requireLocked = true)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            // Both are protected broadcasts only the system can send.
            ContextCompat.registerReceiver(context, screen, filter, ContextCompat.RECEIVER_EXPORTED)
            receiver = screen
        }.onFailure { Log.w(TAG, "lock screen lyrics: could not listen for the screen: ${it.message}") }
    }

    fun detach(context: Context) {
        val registered = receiver ?: return
        receiver = null
        runCatching { context.unregisterReceiver(registered) }
    }

    private fun maybeShow(context: Context, hasTrack: Boolean, requireLocked: Boolean) {
        if (!AppSettings.lockScreenLyrics.value || !hasTrack || !canShow(context)) return
        if (inCall(context)) return
        if (requireLocked) {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (keyguard?.isKeyguardLocked != true) return
        }
        val intent = Intent(context, LockScreenLyricsActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_ACTIVITY_NO_ANIMATION,
        )
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "lock screen lyrics: start refused: ${it.message}") }
    }

    private fun inCall(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
            audio.mode == AudioManager.MODE_RINGTONE
    }
}
