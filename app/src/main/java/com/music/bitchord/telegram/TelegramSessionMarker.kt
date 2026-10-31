package com.music.bitchord.telegram

import android.content.Context
import com.music.bitchord.auth.EncryptedPrefs

/**
 * Whether a Telegram login has actually reached `Ready`.
 *
 * `CLOSED` is both the state before any login and the state a session rests in
 * between requests, so the stage alone cannot say whether an account exists.
 * This marker is written only when TDLib reports `Ready` and cleared on logout,
 * session reset or a session that fell back to phone login. Automatic upgrade
 * walks read it before they treat persisted chat ids as permission to open
 * TDLib, so a fresh install with leftover chat ids cannot look signed in.
 */
internal class TelegramSessionMarker(context: Context) {

    private val prefs = EncryptedPrefs.open(context, "bitchord_telegram", "bitchord_telegram_plain")

    fun load(): Boolean = prefs.getBoolean(KEY_VALIDATED, false)

    fun markValidated() {
        prefs.edit().putBoolean(KEY_VALIDATED, true).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_VALIDATED).apply()
    }

    private companion object {
        const val KEY_VALIDATED = "session_validated"
    }
}
