package com.music.bitchord.playback

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song

/**
 * Whether a row's lyrics timing belongs on a video's clock.
 *
 * A cover row can arrive without its video flag (a Recents or saved-queue row),
 * but the lyrics query still knows it is a cover and therefore a video. The
 * player, the lock screen and the next-song bundle all have to answer this the
 * same way, or the same song gets two different sync keys and shifts.
 */
internal object LyricsTimingSource {

    fun isVideo(song: Song): Boolean = CoverTitles.lyricsQuery(song).isVideo
}
