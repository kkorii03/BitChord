package com.music.bitchord.ko

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.listentogether.JamInviteLink
import com.music.bitchord.data.net.ProxyUrlParser
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SettingsTransferInbox
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.playback.MusicLink
import com.music.bitchord.playback.PlayerDeepLink
import com.music.bitchord.startup.LaunchReplay
import com.music.bitchord.ui.player.PlayerSheetMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val STATE_HANDLED_LAUNCH = "bitchord.handledLaunch"
private const val EXTRA_FORCE_MUSIC_GATE = "bitchord.debug.forceMusicGate"
private const val EXTRA_SEED_HISTORY = "bitchord.debug.seedHistory"
private const val EXTRA_SKIP_ONBOARDING = "bitchord.debug.skipOnboarding"
private const val EXTRA_PROXY = "bitchord.debug.proxy"
private const val DEBUG_TAG = "BitChord"

/** Activity-owned state moved out of the upstream file — see docs/ko-fork-layout.md. */
internal class KoMainActivityState {
    var resumedAtNanos = 0L
    var lastTouchDownNanos = 0L
    var playerMotion: PlayerSheetMotion? = null
    val handledLaunchFingerprints = linkedSetOf<String>()

    fun noteForeground() {
        resumedAtNanos = System.nanoTime()
        lastTouchDownNanos = 0L
    }

    fun noteTouch(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) lastTouchDownNanos = System.nanoTime()
    }

    fun touchedSinceResume(): Boolean = lastTouchDownNanos != 0L

    fun msSinceResume(): Long = (System.nanoTime() - resumedAtNanos) / 1_000_000L

    fun msSinceTouch(): Long =
        if (lastTouchDownNanos == 0L) -1L else (System.nanoTime() - lastTouchDownNanos) / 1_000_000L
}

internal object KoMainActivity {
    fun onStart(state: KoMainActivityState) {
        state.noteForeground()
        InnerTubeXResolver.ensureWarm()
        AppSettings.checkPrivateListeningExpiry()
    }

    fun onResume(state: KoMainActivityState) {
        state.noteForeground()
        state.playerMotion?.resetIfStale()
    }

    /**
     * Consumes the cold-launch intent, returning whether it was a replay.
     * A recreation replays the task's original intent, and that intent may
     * still carry a widget's extra; the request was served the first time and
     * coming back to the app must not raise the player again.
     */
    fun prepareLaunch(
        activity: Activity,
        intent: Intent,
        savedInstanceState: Bundle?,
        state: KoMainActivityState,
    ): Boolean {
        val launchedFromHistory = intent.isLaunchedFromHistory()
        val launchFingerprint = LaunchReplay.fingerprint(intent)
        val savedFingerprints = savedInstanceState
            ?.getStringArrayList(STATE_HANDLED_LAUNCH)
            ?.toSet()
        val replayingLaunch = LaunchReplay.isReplay(
            recreating = savedInstanceState != null,
            launchedFromHistory = launchedFromHistory,
            savedFingerprints = savedFingerprints,
            currentFingerprint = launchFingerprint,
        )
        PlayerDeepLink.consume(
            intent,
            recreating = replayingLaunch,
            launchedFromHistory = launchedFromHistory,
        )
        if (!replayingLaunch) {
            JamInviteLink.consume(intent)
            SettingsTransferInbox.consume(intent)
            MusicLink.consume(intent)
        }
        state.handledLaunchFingerprints.clear()
        state.handledLaunchFingerprints.addAll(savedFingerprints.orEmpty())
        state.handledLaunchFingerprints.add(launchFingerprint)
        applyDebugLaunchSwitches(activity, intent)
        return replayingLaunch
    }

    fun onNewIntent(activity: Activity, intent: Intent, state: KoMainActivityState) {
        val fingerprint = LaunchReplay.fingerprint(intent)
        PlayerDeepLink.consume(
            intent,
            launchedFromHistory = intent.isLaunchedFromHistory(),
        )
        JamInviteLink.consume(intent)
        SettingsTransferInbox.consume(intent)
        MusicLink.consume(intent)
        state.handledLaunchFingerprints += fingerprint
        applyDebugLaunchSwitches(activity, intent)
    }

    fun saveState(state: KoMainActivityState, outState: Bundle) {
        if (state.handledLaunchFingerprints.isNotEmpty()) {
            outState.putStringArrayList(
                STATE_HANDLED_LAUNCH,
                ArrayList(state.handledLaunchFingerprints),
            )
        }
    }

    private fun applyDebugLaunchSwitches(activity: Activity, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.hasExtra(EXTRA_FORCE_MUSIC_GATE)) {
            val forced = intent.getBooleanExtra(EXTRA_FORCE_MUSIC_GATE, false)
            MusicGateFallback.forceForTesting(forced)
            if (forced) Log.d(DEBUG_TAG, "debug: music gate forced")
        }
        if (intent.getBooleanExtra(EXTRA_SEED_HISTORY, false)) {
            (activity as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) {
                val seeded = runCatching { ListeningStats.seedIfEmpty() }
                    .onFailure { Log.w(DEBUG_TAG, "debug: seed failed: ${it.message}") }
                    .getOrDefault(0)
                Log.d(DEBUG_TAG, "debug: seeded $seeded plays")
            }
        }
        if (intent.getBooleanExtra(EXTRA_SKIP_ONBOARDING, false)) {
            AppSettings.setOnboardingComplete()
            Log.d(DEBUG_TAG, "debug: onboarding skipped")
        }
        if (intent.hasExtra(EXTRA_PROXY)) {
            val config = ProxyUrlParser.parse(intent.getStringExtra(EXTRA_PROXY))
            if (config != null) {
                AppSettings.setProxyConfig(config)
                Log.d(
                    DEBUG_TAG,
                    "debug: proxy ${config.mode} ${config.host}:${config.port} scope=${config.scope}",
                )
            } else {
                Log.w(DEBUG_TAG, "debug: proxy URL ignored (invalid)")
            }
        }
    }
}

/**
 * Whether the system relaunched this activity from the recents history rather
 * than starting it afresh. Such a launch re-delivers the task's original
 * intent, and it does so with no saved state, so `savedInstanceState` alone
 * cannot tell it apart from a real widget tap.
 */
internal fun Intent.isLaunchedFromHistory(): Boolean =
    flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
