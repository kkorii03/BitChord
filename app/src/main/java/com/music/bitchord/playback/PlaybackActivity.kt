package com.music.bitchord.playback

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

    fun onPlayingChanged(value: Boolean) {
        playing = value
    }

    fun onBufferingChanged(value: Boolean) {
        buffering = value
    }

    fun stopped() {
        playing = false
        buffering = false
    }
}
