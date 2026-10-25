package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules that keep the song-version lookup from turning into a hot-swap
 * mid-song, and from asking a signed-out `next` for a counterpart it never
 * returns.
 */
class SongVersionPolicyTest {

    @Test
    fun `four empty counterpart answers are not yet a verdict`() {
        assertFalse(SongVersionPolicy.counterpartDisabled(0))
        assertFalse(SongVersionPolicy.counterpartDisabled(4))
    }

    @Test
    fun `five empty counterpart answers disable it for the session`() {
        assertTrue(SongVersionPolicy.counterpartDisabled(5))
        assertTrue(SongVersionPolicy.counterpartDisabled(9))
    }

    @Test
    fun `the limit is the documented five`() {
        assertEquals(5, SongVersionPolicy.COUNTERPART_EMPTY_LIMIT)
    }

    @Test
    fun `a fresh track at position zero may still be swapped`() {
        assertTrue(
            SongVersionPolicy.mayAutoSwapBeforeAudio(
                positionMs = 0L,
                isPlaying = false,
                alreadyAudible = false,
            ),
        )
    }

    @Test
    fun `a track that has moved past zero may not be swapped`() {
        assertFalse(
            SongVersionPolicy.mayAutoSwapBeforeAudio(
                positionMs = 1L,
                isPlaying = false,
                alreadyAudible = false,
            ),
        )
    }

    @Test
    fun `a playing track may not be swapped even at zero`() {
        assertFalse(
            SongVersionPolicy.mayAutoSwapBeforeAudio(
                positionMs = 0L,
                isPlaying = true,
                alreadyAudible = false,
            ),
        )
    }

    @Test
    fun `a track already audible may not be swapped`() {
        assertFalse(
            SongVersionPolicy.mayAutoSwapBeforeAudio(
                positionMs = 0L,
                isPlaying = false,
                alreadyAudible = true,
            ),
        )
    }

    @Test
    fun `the before-audio wait is one and a half seconds`() {
        assertEquals(1_500L, SongVersionPolicy.BEFORE_AUDIO_TIMEOUT_MS)
    }

    @Test
    fun `a previous track playing is not this row's first sound`() {
        assertFalse(SongVersionPolicy.hasMadeFirstSound("new", "old"))
        assertFalse(SongVersionPolicy.hasMadeFirstSound("new", null))
        assertTrue(SongVersionPolicy.hasMadeFirstSound("new", "new"))
    }

    @Test
    fun `only videos and type-unknown preview rows need a lookup`() {
        assertTrue(SongVersionPolicy.wantsSongVersion(isVideo = true, typeUnknown = false))
        assertTrue(SongVersionPolicy.wantsSongVersion(isVideo = false, typeUnknown = true))
        assertFalse(SongVersionPolicy.wantsSongVersion(isVideo = false, typeUnknown = false))
    }

    @Test
    fun `an undecided video is not prefetched by its own id`() {
        assertEquals(
            SongVersionPolicy.AheadSongVersion.UNDECIDED,
            SongVersionPolicy.aheadSongVersion(
                isVideo = true,
                typeUnknown = false,
                hasVersion = false,
                knownMissing = false,
            ),
        )
        assertEquals(
            SongVersionPolicy.AheadSongVersion.UNDECIDED,
            SongVersionPolicy.aheadSongVersion(
                isVideo = false,
                typeUnknown = true,
                hasVersion = false,
                knownMissing = false,
            ),
        )
    }

    @Test
    fun `a decided version points the prefetch at the version`() {
        assertEquals(
            SongVersionPolicy.AheadSongVersion.FOUND,
            SongVersionPolicy.aheadSongVersion(
                isVideo = true,
                typeUnknown = false,
                hasVersion = true,
                knownMissing = false,
            ),
        )
    }

    @Test
    fun `a decided absence keeps the row's own id`() {
        assertEquals(
            SongVersionPolicy.AheadSongVersion.NONE,
            SongVersionPolicy.aheadSongVersion(
                isVideo = true,
                typeUnknown = false,
                hasVersion = false,
                knownMissing = true,
            ),
        )
    }

    @Test
    fun `a catalogue row needs no version wait`() {
        assertEquals(
            SongVersionPolicy.AheadSongVersion.NOT_WANTED,
            SongVersionPolicy.aheadSongVersion(
                isVideo = false,
                typeUnknown = false,
                hasVersion = false,
                knownMissing = false,
            ),
        )
    }

    @Test
    fun `a downloaded video still gets its song version`() {
        assertFalse(
            SongVersionPolicy.keepsLocalFile(
                localFile = true,
                isVideo = true,
                typeUnknown = false,
            ),
        )
        assertFalse(
            SongVersionPolicy.keepsLocalFile(
                localFile = true,
                isVideo = false,
                typeUnknown = true,
            ),
        )
    }

    @Test
    fun `a downloaded song or the listener's own file is kept`() {
        assertTrue(
            SongVersionPolicy.keepsLocalFile(
                localFile = true,
                isVideo = false,
                typeUnknown = false,
            ),
        )
    }

    @Test
    fun `a streamed row is never a kept local file`() {
        assertFalse(
            SongVersionPolicy.keepsLocalFile(
                localFile = false,
                isVideo = false,
                typeUnknown = false,
            ),
        )
    }
}
