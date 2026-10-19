package com.music.bitchord.ui.player

import com.music.bitchord.data.DebugLog

/** What asked the full player to open, for the diagnostic log. */
enum class PlayerExpandSource(val logName: String) {
    /** A tap on the mini player or the glass bar's now-playing accessory. */
    TAP("tap"),

    /** A pull on the mini player. */
    PULL("pull"),

    /** A widget's artwork, relayed through [com.music.bitchord.playback.PlayerDeepLink]. */
    DEEPLINK("deeplink"),

    /** The sheet moving the player itself, rather than the user. */
    MOTION("motion"),
}

/**
 * Whether something may raise the full player.
 *
 * The player must not open by itself: a programmatic raise while the app comes
 * back to the foreground — after Home or Back — used to leave the sheet
 * expanded with nobody having asked for it. Every raise that follows the user
 * has an `ACTION_DOWN` counted by the activity since it last resumed; only a
 * widget's deep link, which arrives as an intent rather than a gesture, is
 * allowed without one. A real tap and a real pull both always have that down,
 * so they pass.
 *
 * Kept free of Android types so the decision is testable on its own.
 */
object PlayerExpandGuard {

    /** The tag the phone routes into its exportable log. */
    private const val TAG = "BitChord"

    fun allow(source: PlayerExpandSource, touchedSinceResume: Boolean): Boolean =
        source == PlayerExpandSource.DEEPLINK || touchedSinceResume

    /** One line per raise of the player, with the app frames that asked for it. */
    fun logExpand(source: PlayerExpandSource, msSinceResume: Long, msSinceTouch: Long) {
        DebugLog.d(
            TAG,
            "player expand: source=${source.logName} msSinceResume=$msSinceResume " +
                "msSinceTouch=$msSinceTouch stack=${appFrames()}",
        )
    }

    /** One line per raise refused for want of a touch, with the same frames. */
    fun logRefused(source: PlayerExpandSource, msSinceResume: Long) {
        DebugLog.d(
            TAG,
            "player expand refused: no touch since resume source=${source.logName} " +
                "msSinceResume=$msSinceResume stack=${appFrames()}",
        )
    }

    /**
     * The first few app frames of the caller, innermost first — this guard
     * itself skipped, so the stack starts at whoever asked the player open.
     */
    private fun appFrames(limit: Int = 5): String =
        Throwable().stackTrace
            .filter { it.className.startsWith("com.music.bitchord") }
            .filterNot { it.className.startsWith("com.music.bitchord.ui.player.PlayerExpandGuard") }
            .take(limit)
            .joinToString(">") { frame ->
                "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
            }
}
