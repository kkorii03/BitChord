package com.music.bitchord.widget

import android.content.Context
import com.music.bitchord.data.settings.AppSettings

/**
 * The one decision both widget providers make when they build their tap
 * intent: whether the app should come forward on the full player or on
 * whatever screen it was left on.
 *
 * Kept apart from [MediaWidget.openPlayer] and [MediaWidgetPill.openPlayer] so
 * the rule is a pure function the tests can read, and so the two providers
 * cannot drift apart on it.
 */
internal object WidgetOpenIntent {

    /**
     * [setting] is [AppSettings.widgetOpensPlayer]'s value; the tap expands
     * exactly when the listener asked for it. Off is the default, which makes
     * a widget tap an ordinary re-entry into the app.
     */
    fun shouldExpand(setting: Boolean): Boolean = setting

    /** The switch as it stands in this process, read the way a widget can. */
    fun enabled(context: Context): Boolean =
        shouldExpand(AppSettings.readWidgetOpensPlayer(context.applicationContext))
}
