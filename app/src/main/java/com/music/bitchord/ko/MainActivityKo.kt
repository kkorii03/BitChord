package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy

/**
 * Rows from a preview listing (a downloaded copy shown while the network
 * response is still on its way) do not know whether they are videos. Keep
 * them as audio rows but mark the type unknown, so the service and the
 * pre-audio lookup still ask for a song version — see [Song.typeUnknown].
 */
internal fun List<Song>.asTypeUnknown(): List<Song> =
    map {
        if (!it.isVideo &&
            !SongVersionPolicy.keepsLocalFile(it.localUri != null, it.isVideo, it.typeUnknown)
        ) {
            it.copy(typeUnknown = true)
        } else {
            it
        }
    }

/** Tracks whose automatic before-audio music-only swap has been settled. */
internal val musicOnlyTried: MutableSet<String> =
    java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
