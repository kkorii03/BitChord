package com.music.bitchord.playback

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request from outside the app to open the full player.
 *
 * Tapping a widget's artwork should land on the now-playing sheet, and there is
 * nothing to hand that intent to: whether the sheet is up is `showNowPlaying`, a
 * `remember`ed boolean inside `BitChordApp`, which only exists once the
 * composition is running and which
 * [MainActivity][com.music.bitchord.MainActivity] cannot reach. Because the
 * activity is `singleTask`, the intent also arrives down two different paths
 * depending on whether the app happened to be alive — `onCreate` for a cold
 * launch, `onNewIntent` for every other time — so both relay through here and
 * the composition watches this.
 */
object PlayerDeepLink {

    /** Set on the widget's artwork intent. Nothing else sets it. */
    const val EXTRA_OPEN_PLAYER = "bitchord.openPlayer"

    /** Marks an intent this process has already read, so a replay cannot re-arm it. */
    private const val EXTRA_CONSUMED = "bitchord.playerDeepLinkConsumed"

    private val _pending = MutableStateFlow(false)

    /** Whether a request is outstanding. Cleared by [handled]. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /**
     * Whether a launch intent should raise the player.
     *
     * A request only counts when it arrives on a *fresh* launch. A singleTask
     * activity keeps the intent that started its task and hands it back to
     * `onCreate` on every recreation (a theme change, the system reclaiming the
     * activity while it is in the background, a task restored from Recents).
     * Re-applying the widget's extra there raised the sheet by itself when the
     * listener merely came back to the app — the request had already been
     * served once and must stay spent. [recreating] is
     * `savedInstanceState != null`, which is exactly the case where the intent
     * is being replayed rather than newly delivered.
     *
     * [launchedFromHistory] is the second replay path, one `savedInstanceState`
     * does not catch: after the activity has finished, picking the app out of
     * Recents (or an OEM launcher re-sending the task's base intent) starts a
     * new instance from the old intent with no saved state. Android flags that
     * launch with [Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY] on the delivered
     * intent, which is what separates it from a real widget tap.
     */
    fun shouldExpandOnIntent(
        requested: Boolean,
        recreating: Boolean,
        launchedFromHistory: Boolean,
    ): Boolean = requested && !recreating && !launchedFromHistory

    /**
     * Reads an incoming intent, and reports whether it asked for the player.
     *
     * [recreating] must be true when this is [MainActivity][com.music.bitchord.MainActivity]'s
     * `onCreate` running from saved state; see [shouldExpandOnIntent]. The
     * extra is taken off the intent either way, so a stale one cannot be read
     * again by a later call.
     */
    fun consume(
        intent: Intent?,
        recreating: Boolean = false,
        launchedFromHistory: Boolean = false,
    ): Boolean {
        val alreadyConsumed = intent?.getBooleanExtra(EXTRA_CONSUMED, false) == true
        val requested =
            !alreadyConsumed && intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true
        // Cleared off the intent as well. A singleTask activity keeps the intent
        // that launched it, and getIntent() returns the same one after every
        // configuration change — left in place, a widget tap would reopen the
        // sheet each time the device changed theme or font size.
        intent?.removeExtra(EXTRA_OPEN_PLAYER)
        // A marker as well as the removal: a replayed intent restored from the
        // task can carry its extras again, and this object may outlive the
        // activity that first read it.
        intent?.putExtra(EXTRA_CONSUMED, true)
        // Replaces rather than accumulates: the latest intent is the only
        // request that can still be outstanding, and an activity that is
        // starting fresh must not inherit one from a previous instance.
        _pending.value = shouldExpandOnIntent(requested, recreating, launchedFromHistory)
        return _pending.value
    }

    /**
     * Called once the player has actually been opened.
     *
     * The flag has to be cleared by whoever acts on it, not by whoever set it:
     * this object outlives the composition, so a request left standing would be
     * served again by the next composition — which is to say, the sheet would
     * spring back open the first time the activity was recreated after the user
     * dismissed it.
     */
    fun handled() {
        _pending.value = false
    }
}
