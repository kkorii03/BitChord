package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.MusicGateFallback

/** The music-gate verdict, kept beside the desktop's other preferences. */
internal object DesktopMusicGateStore : MusicGateFallback.Store {
    private val persistence by lazy { DesktopPersistence() }

    override fun getString(key: String): String? =
        persistence.string(PREFIX + key, "").takeIf { it.isNotEmpty() }

    override fun putString(key: String, value: String) =
        persistence.saveString(PREFIX + key, value)

    private const val PREFIX = "music_gate_"
}
