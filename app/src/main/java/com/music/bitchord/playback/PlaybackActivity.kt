package com.music.bitchord.playback

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether a listener is playing or buffering right now, as the playback
 * service sees it.
 *
 * The automatic high-quality pass must not open a second request to a server
 * the player is already streaming from. It reads this once when its worker
 * starts and is woken by the pause/buffer-idle event rather than polling; see
 * [com.music.bitchord.download.HqLibraryUpgrade.scheduleIfEnabled].
 */
internal object PlaybackActivity {

    @Volatile
    private var playing: Boolean = false

    @Volatile
    private var buffering: Boolean = false

    val active: Boolean get() = playing || buffering

    private val playingListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val bufferingListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addPlayingListener(listener: (Boolean) -> Unit) {
        playingListeners += listener
    }

    fun addBufferingListener(listener: (Boolean) -> Unit) {
        bufferingListeners += listener
    }

    fun onPlayingChanged(value: Boolean) {
        playing = value
        playingListeners.forEach { runCatching { it(value) } }
    }

    fun onBufferingChanged(value: Boolean) {
        buffering = value
        bufferingListeners.forEach { runCatching { it(value) } }
    }

    fun stopped() {
        val wasActive = active
        playing = false
        buffering = false
        if (wasActive) {
            playingListeners.forEach { runCatching { it(false) } }
            bufferingListeners.forEach { runCatching { it(false) } }
        }
    }
}
