package com.music.bitchord.widget

import android.content.Context
import com.music.bitchord.data.StoredSongDisplay
import com.music.bitchord.data.cover.videoThumbnailUrl
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongOrigins

/**
 * Everything a home-screen widget needs to know about playback.
 *
 * Persisted rather than read live, because a widget outlives the app. It is on
 * screen while the process is dead, after a reboot, and in the seconds before a
 * launcher's first update reaches us — none of which a
 * [MediaController][androidx.media3.session.MediaController] can serve, since
 * connecting one means starting [PlaybackService][com.music.bitchord.playback.PlaybackService]
 * just to find out what to draw. So the service writes here whenever the answer
 * changes ([publishWidgetState][com.music.bitchord.playback.PlaybackService]) and
 * the widget only ever reads a file.
 */
internal data class MediaWidgetSnapshot(
    val mediaId: String?,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    /**
     * Whether the transport should show a pause glyph.
     *
     * This tracks `player.playWhenReady`, **not** `player.isPlaying`. The
     * difference matters more here than almost anywhere else in the app: a
     * YouTube track has to be resolved through NewPipe before it can buffer, and
     * that can run for seconds, all of which `isPlaying` spends false. Keyed on
     * it, a widget would answer a tap by leaving the play glyph exactly where it
     * was — the one thing that makes a control feel broken. `playWhenReady`
     * flips the instant the command lands, which is also what the media
     * notification shows.
     */
    val isPlaying: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    /** Whether the track is liked. Drawn by the 4×1 widget's star. */
    val isLiked: Boolean = false,
    /**
     * Whether Like is offered for this track at all — the player's own rule:
     * signed in, and not a local file or a download. The star is hidden, not
     * disabled, when it is false, as it is in the player.
     */
    val canLike: Boolean = false,
    /** Whether shuffle is on. Drawn by the 4×1 widget's Shuffle. */
    val shuffleEnabled: Boolean = false,
) {
    /** Whether there is a track to draw at all. */
    val hasTrack: Boolean get() = mediaId != null

    companion object {

        val EMPTY = MediaWidgetSnapshot(
            mediaId = null,
            title = "",
            artist = "",
            artworkUrl = null,
            isPlaying = false,
            hasPrevious = false,
            hasNext = false,
        )

        /**
         * The snapshot the service publishes for what is current now.
         *
         * A [song] of null means the queue no longer has a track. That has to
         * produce [EMPTY] rather than be skipped by the caller: widgets are
         * persisted, so leaving the last snapshot in place leaves the last
         * track's cover and title on the home screen after the queue was
         * cleared.
         */
        fun fromPlayback(
            song: Song?,
            isPlaying: Boolean,
            hasPrevious: Boolean,
            hasNext: Boolean,
            isLiked: Boolean,
            canLike: Boolean,
            shuffleEnabled: Boolean,
        ): MediaWidgetSnapshot {
            if (song == null) return EMPTY
            val shown = StoredSongDisplay.clean(song)
            return MediaWidgetSnapshot(
                mediaId = shown.videoId,
                title = shown.title,
                artist = shown.artist,
                artworkUrl = artworkUrlFor(shown),
                isPlaying = isPlaying,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                isLiked = isLiked,
                canLike = canLike,
                shuffleEnabled = shuffleEnabled,
            )
        }

        /**
         * The cover URL a widget should draw.
         *
         * [Song.thumbnailUrl] is the row's own picture, but a restored queue or
         * a playlist row can arrive without one even though its video id still
         * has a YouTube still. The notification already falls back that way
         * through `artworkSelection`; the widget used the raw field and drew the
         * placeholder instead.
         */
        fun artworkUrlFor(song: Song): String? {
            val recorded = song.thumbnailUrl?.takeIf { it.isNotBlank() }
            if (recorded != null) return recorded
            if (SongOrigins.isLocalFile(song)) return null
            return videoThumbnailUrl(song.videoId)
        }

        fun save(context: Context, snapshot: MediaWidgetSnapshot) {
            prefs(context).edit()
                .putString(KEY_MEDIA_ID, snapshot.mediaId)
                .putString(KEY_TITLE, snapshot.title)
                .putString(KEY_ARTIST, snapshot.artist)
                .putString(KEY_ARTWORK, snapshot.artworkUrl)
                .putBoolean(KEY_PLAYING, snapshot.isPlaying)
                .putBoolean(KEY_HAS_PREVIOUS, snapshot.hasPrevious)
                .putBoolean(KEY_HAS_NEXT, snapshot.hasNext)
                .putBoolean(KEY_LIKED, snapshot.isLiked)
                .putBoolean(KEY_CAN_LIKE, snapshot.canLike)
                .putBoolean(KEY_SHUFFLE, snapshot.shuffleEnabled)
                .apply()
        }

        /**
         * Drops the persisted "liked" flag, for a sign-out or account switch.
         * The rating belonged to the account that just left, and the service
         * would otherwise re-save it and show the old account's heart.
         */
        fun clearLiked(context: Context) {
            prefs(context).edit().putBoolean(KEY_LIKED, false).apply()
        }

        /** The last state published by the live playback service. */
        fun load(context: Context): MediaWidgetSnapshot {
            val prefs = prefs(context)
            val mediaId = prefs.getString(KEY_MEDIA_ID, null)
            if (mediaId != null) {
                // A snapshot written before the display cleaner learned about
                // soundtrack labels still holds the old title and artist on
                // the launcher until the next play publishes a new one.
                val stored = StoredSongDisplay.clean(
                    Song(
                        videoId = mediaId,
                        title = prefs.getString(KEY_TITLE, "").orEmpty(),
                        artist = prefs.getString(KEY_ARTIST, "").orEmpty(),
                        thumbnailUrl = null,
                    ),
                )
                return MediaWidgetSnapshot(
                    mediaId = mediaId,
                    title = stored.title,
                    artist = stored.artist,
                    artworkUrl = prefs.getString(KEY_ARTWORK, null),
                    isPlaying = prefs.getBoolean(KEY_PLAYING, false),
                    hasPrevious = prefs.getBoolean(KEY_HAS_PREVIOUS, false),
                    hasNext = prefs.getBoolean(KEY_HAS_NEXT, false),
                    isLiked = prefs.getBoolean(KEY_LIKED, false),
                    canLike = prefs.getBoolean(KEY_CAN_LIKE, false),
                    shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE, false),
                )
            }
            return EMPTY
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences("bitchord_widget", Context.MODE_PRIVATE)

        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_ARTWORK = "artwork"
        private const val KEY_PLAYING = "playing"
        private const val KEY_HAS_PREVIOUS = "has_previous"
        private const val KEY_HAS_NEXT = "has_next"
        private const val KEY_LIKED = "liked"
        private const val KEY_CAN_LIKE = "can_like"
        private const val KEY_SHUFFLE = "shuffle"
    }
}
