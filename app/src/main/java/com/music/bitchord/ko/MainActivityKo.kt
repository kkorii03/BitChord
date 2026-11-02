package com.music.bitchord.ko

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.MusicGateFallback
import com.music.bitchord.data.net.ProxyUrlParser
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.startup.LaunchReplay
import com.music.bitchord.ui.player.PlayerSheetMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy

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

internal data class KoLaunchDecision(
    val replaying: Boolean,
    val launchedFromHistory: Boolean,
)

internal object KoMainActivity {
    fun onStart(state: KoMainActivityState) {
        state.noteForeground()
        AppSettings.checkPrivateListeningExpiry()
    }

    /**
     * The cold InnerTubeX warm-up belongs to the first frame actually drawn,
     * not to an activity that was started in the background (a car, an OEM
     * music button, a widget) and never shown. A play still starts it itself
     * through [InnerTubeXResolver.ensureWarm], so first sound is not delayed.
     */
    fun warmOnFirstScreen(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val observer = decor.viewTreeObserver ?: return
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val live = decor.viewTreeObserver
                if (live.isAlive) live.removeOnPreDrawListener(this)
                InnerTubeXResolver.ensureWarm()
                return true
            }
        })
    }

    fun onResume(state: KoMainActivityState) {
        state.noteForeground()
        state.playerMotion?.resetIfStale()
    }

    /**
     * Decides whether the cold-launch intent is a replay. A recreation replays
     * the task's original intent, and that intent may still carry a widget's
     * extra; the request was served the first time and coming back to the app
     * must not raise the player again.
     */
    fun prepareLaunch(
        intent: Intent,
        savedInstanceState: Bundle?,
        state: KoMainActivityState,
    ): KoLaunchDecision {
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
        state.handledLaunchFingerprints.clear()
        state.handledLaunchFingerprints.addAll(savedFingerprints.orEmpty())
        state.handledLaunchFingerprints.add(launchFingerprint)
        return KoLaunchDecision(replayingLaunch, launchedFromHistory)
    }

    /** The fingerprint is taken before MainActivity's relays consume the extras. */
    fun newIntentFingerprint(intent: Intent): String = LaunchReplay.fingerprint(intent)

    fun onNewIntent(
        activity: Activity,
        intent: Intent,
        fingerprint: String,
        state: KoMainActivityState,
    ) {
        state.handledLaunchFingerprints += fingerprint
        applyDebugLaunchSwitches(activity, intent)
    }

    fun afterLaunch(activity: Activity, intent: Intent) {
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
/**
 * Rows from a preview listing (a downloaded copy shown while the network
 * response is still on its way) do not know whether they are videos. Keep
 * them as audio rows but mark the type unknown, so the service and the
 * pre-audio lookup still ask for a song version — see [Song.typeUnknown].
 */
