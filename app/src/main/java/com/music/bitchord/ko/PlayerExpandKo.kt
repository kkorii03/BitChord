package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.ui.player.PlayerExpandGuard
import com.music.bitchord.ui.player.PlayerExpandSource

/**
 * The one way the player is raised, whoever asks: M3 moving it, the app moving
 * it, or a widget's deep link. A raise has to follow a touch the activity saw
 * since it last resumed — or be that deep link — so a programmatic open while
 * the app is coming back to the foreground cannot raise the sheet by itself.
 */
internal fun koSetPlayerShown(
    shown: Boolean,
    source: PlayerExpandSource,
    currentlyShown: Boolean,
    touchedSinceResume: Boolean,
    msSinceResume: Long,
    msSinceTouch: Long,
    onShownChange: (Boolean) -> Unit,
) {
    if (!shown) {
        if (currentlyShown) {
            onShownChange(false)
            TrackLog.i("BitChord", "nav: Player close source=${source.logName}")
        }
    } else if (!currentlyShown) {
        if (PlayerExpandGuard.allow(source, touchedSinceResume)) {
            onShownChange(true)
            TrackLog.i("BitChord", "nav: Player open source=${source.logName}")
            PlayerExpandGuard.logExpand(source, msSinceResume, msSinceTouch)
        } else {
            PlayerExpandGuard.logRefused(source, msSinceResume)
        }
    }
}

/** The activity's one raise/close entry point, kept out of the upstream file. */
internal fun koPlayerShownSetter(
    currentlyShown: () -> Boolean,
    touchedSinceResume: () -> Boolean,
    msSinceResume: () -> Long,
    msSinceTouch: () -> Long,
    onShownChange: (Boolean) -> Unit,
): (Boolean, PlayerExpandSource) -> Unit = { shown, source ->
    koSetPlayerShown(
        shown = shown,
        source = source,
        currentlyShown = currentlyShown(),
        touchedSinceResume = touchedSinceResume(),
        msSinceResume = msSinceResume(),
        msSinceTouch = msSinceTouch(),
        onShownChange = onShownChange,
    )
}
