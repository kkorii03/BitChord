package com.music.bitchord.ko

import com.music.bitchord.data.StoredSongDisplay
import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.TrackEntry

/**
 * [KO] listening-display
 *
 * The fork's display rules around stored listening rows: cover bylines are
 * credited to the singer and cleaned before they reach a playlist or Replay.
 */
internal object KoListeningStatsDisplay {

    fun clean(song: Song): Song = StoredSongDisplay.clean(song)

    fun creditedArtist(song: Song): String = CoverTitles.creditedArtist(song)

    /**
     * The entry as a playable row. A cover was recorded as it was shown —
     * its song, and "원곡자 · 가수 커버" — so it is marked a video again: that
     * byline is what CoverTitles reads a cover back from, for its lyrics,
     * timing and moving cover.
     */
    fun asSong(entry: TrackEntry): Song = StoredSongDisplay.clean(
        Song(
            videoId = entry.id,
            title = entry.title,
            artist = entry.artist,
            thumbnailUrl = entry.art,
            durationText = entry.durationText,
            artistId = entry.artistId,
            albumId = entry.albumId,
            albumName = entry.album,
            isVideo = entry.artist.endsWith(" 커버"),
        ),
    )
}
