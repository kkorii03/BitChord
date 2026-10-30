package com.music.bitchord.playback

import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.widget.MediaWidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The notification's star is read after a process death, when LikeState has not
 * seen this session's library yet. The persisted widget snapshot is the only
 * surviving statement about the last published rating, so it has to decide both
 * the glyph and which way the next tap goes.
 */
class SessionFavoriteStateTest {

    private val likedSnapshot = MediaWidgetSnapshot(
        mediaId = "dQw4w9WgXcQ",
        title = "Song",
        artist = "Artist",
        artworkUrl = null,
        isPlaying = true,
        hasPrevious = true,
        hasNext = true,
        isLiked = true,
        canLike = true,
    )

    @Test
    fun `the persisted like survives a process restart`() {
        assertEquals(
            LikeStatus.LIKE,
            SessionFavoriteState.status(
                videoId = "dQw4w9WgXcQ",
                override = null,
                persisted = likedSnapshot,
            ),
        )
    }

    @Test
    fun `the first tap after a restart unlikes a persisted like`() {
        assertEquals(
            LikeStatus.INDIFFERENT,
            SessionFavoriteState.toggled(
                videoId = "dQw4w9WgXcQ",
                override = null,
                persisted = likedSnapshot,
            ),
        )
    }

    @Test
    fun `a snapshot for another track cannot lend its like`() {
        assertEquals(
            LikeStatus.INDIFFERENT,
            SessionFavoriteState.status(
                videoId = "aaaaaaaaaaa",
                override = null,
                persisted = likedSnapshot,
            ),
        )
    }

    @Test
    fun `a session override wins over the persisted snapshot`() {
        assertEquals(
            LikeStatus.INDIFFERENT,
            SessionFavoriteState.status(
                videoId = "dQw4w9WgXcQ",
                override = LikeStatus.INDIFFERENT,
                persisted = likedSnapshot,
            ),
        )
        assertEquals(
            LikeStatus.LIKE,
            SessionFavoriteState.toggled(
                videoId = "dQw4w9WgXcQ",
                override = LikeStatus.INDIFFERENT,
                persisted = likedSnapshot,
            ),
        )
    }
}
