package com.music.bitchord.startup

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import com.music.bitchord.playback.PlayerDeepLink

/**
 * Whether the launch intent in hand is the one this task already served, rather
 * than a new request from outside the app.
 *
 * A singleTask activity keeps the intent that started its task and hands it
 * back to `onCreate` whenever the process is recreated. The extras the link
 * relays write are only in the client-side copy, so after a process death the
 * original, unconsumed intent comes back. Without this the app replayed a
 * shared song, re-joined a party, or reopened an import preview with no user
 * action at all.
 *
 * The saved fingerprints are what separate a restored launch from a genuinely
 * new intent delivered to a recreated activity: the latter carries different
 * action/data/extras, so it is still served.
 *
 * Every intent this task has served is remembered, not just the most recent
 * one. A `singleTask` activity replaces its intent with [MainActivity]'s
 * `setIntent` on each new arrival, but Android recreates the task from the
 * *original* base intent after a process death. Keeping only the newest
 * fingerprint made that original look new again, so a shared link or party
 * invite the user had already used was replayed on the next app start.
 */
internal object LaunchReplay {

    fun isReplay(
        recreating: Boolean,
        launchedFromHistory: Boolean,
        savedFingerprints: Set<String>?,
        currentFingerprint: String,
    ): Boolean {
        if (launchedFromHistory) return true
        if (!recreating) return false
        // A recreation with no saved fingerprints is either the first launch
        // after an upgrade or a state we cannot compare; serving it again is
        // the worse failure, so it is treated as a replay.
        if (savedFingerprints.isNullOrEmpty()) return true
        return currentFingerprint in savedFingerprints
    }

    /** The parts of an intent that decide what it asks the app to do. */
    fun fingerprint(intent: Intent?): String {
        if (intent == null) return ""
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString().orEmpty()
        return fingerprintOf(
            action = intent.action,
            data = intent.dataString,
            type = intent.type,
            text = intent.getStringExtra(Intent.EXTRA_TEXT),
            query = intent.getStringExtra(SearchManager.QUERY),
            stream = stream,
            openPlayer = intent.getBooleanExtra(PlayerDeepLink.EXTRA_OPEN_PLAYER, false),
        )
    }

    /**
     * [fingerprint]'s fields, split out so a test can prove that a widget's
     * player request is part of what makes an intent distinct without an
     * Android runtime to build an [Intent].
     *
     * Every widget-artwork intent carries only [openPlayer]; with that left
     * out they all hashed to the same empty string, so a new widget tap
     * delivered to a recreated activity was mistaken for the task's restored
     * original and silently dropped.
     */
    internal fun fingerprintOf(
        action: String?,
        data: String?,
        type: String?,
        text: String?,
        query: String?,
        stream: String?,
        openPlayer: Boolean,
    ): String = buildString {
        append(action.orEmpty()).append('\u0000')
        append(data.orEmpty()).append('\u0000')
        append(type.orEmpty()).append('\u0000')
        append(text.orEmpty()).append('\u0000')
        append(query.orEmpty()).append('\u0000')
        append(stream.orEmpty()).append('\u0000')
        append(openPlayer)
    }
}
