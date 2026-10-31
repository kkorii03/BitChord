package com.music.bitchord.telegram

import android.content.Context
import com.music.bitchord.auth.EncryptedPrefs

/**
 * The chats Telegram search is allowed to look in.
 *
 * Only chat ids are stored; names stay in Telegram and are fetched when the
 * settings screen asks. The list lives in encrypted preferences like the other
 * source configuration, and a logout clears it with the session.
 */
internal class TelegramChatSelection(context: Context) {

    private val prefs = EncryptedPrefs.open(context, "bitchord_telegram", "bitchord_telegram_plain")

    fun load(): Set<Long> = prefs.getString(KEY_CHATS, null)
        ?.split(',')
        ?.mapNotNull { it.trim().toLongOrNull() }
        ?.toSet()
        .orEmpty()

    fun save(ids: Set<Long>) {
        prefs.edit().putString(KEY_CHATS, ids.sorted().joinToString(",")).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_CHATS).apply()
    }

    private companion object {
        const val KEY_CHATS = "chats"
    }
}
