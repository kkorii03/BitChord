package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `a file four seconds longer than the song is the video's sound`() {
        assertEquals(
            4_000L,
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = 210_700L,
                catalogDurationMs = 206_700L,
                isVideo = false,
                localFile = true,
                soundSourceId = null,
            ),
        )
    }

    @Test
    fun `a file less than two and a half seconds longer is not suspected`() {
        assertNull(
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = 207_500L,
                catalogDurationMs = 206_700L,
                isVideo = false,
                localFile = true,
                soundSourceId = null,
            ),
        )
    }

    @Test
    fun `missing either length is not evidence`() {
        assertNull(
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = null,
                catalogDurationMs = 206_700L,
                isVideo = false,
                localFile = true,
                soundSourceId = null,
            ),
        )
        assertNull(
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = 210_700L,
                catalogDurationMs = null,
                isVideo = false,
                localFile = true,
                soundSourceId = null,
            ),
        )
    }

    @Test
    fun `a video row is not a song-version length question`() {
        assertNull(
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = 210_700L,
                catalogDurationMs = 206_700L,
                isVideo = true,
                localFile = true,
                soundSourceId = null,
            ),
        )
    }

    @Test
    fun `a recorded sound source is left to the provenance rule`() {
        assertNull(
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = 210_700L,
                catalogDurationMs = 206_700L,
                isVideo = false,
                localFile = true,
                soundSourceId = "JucvYrdSIcM",
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

    @Test
    fun `an unknown-provenance download is verified online but kept offline`() {
        assertTrue(
            SongVersionPolicy.verifiesUnknownDownload(
                localFile = true,
                downloaded = true,
                provenanceKnown = false,
                isVideo = false,
                typeUnknown = false,
                offline = false,
            ),
        )
        assertFalse(
            SongVersionPolicy.verifiesUnknownDownload(
                localFile = true,
                downloaded = true,
                provenanceKnown = false,
                isVideo = false,
                typeUnknown = false,
                offline = true,
            ),
        )
    }

    @Test
    fun `a known download or the listener's own file is not re-verified`() {
        assertFalse(
            SongVersionPolicy.verifiesUnknownDownload(
                localFile = true,
                downloaded = true,
                provenanceKnown = true,
                isVideo = false,
                typeUnknown = false,
                offline = false,
            ),
        )
        assertFalse(
            SongVersionPolicy.verifiesUnknownDownload(
                localFile = true,
                downloaded = false,
                provenanceKnown = false,
                isVideo = false,
                typeUnknown = false,
                offline = false,
            ),
        )
    }

    @Test
    fun `a video or unknown-type row keeps its own version path`() {
        assertFalse(
            SongVersionPolicy.verifiesUnknownDownload(
                localFile = true,
                downloaded = true,
                provenanceKnown = false,
                isVideo = true,
                typeUnknown = false,
                offline = false,
            ),
        )
        assertFalse(
            SongVersionPolicy.verifiesUnknownDownload(
                localFile = true,
                downloaded = true,
                provenanceKnown = false,
                isVideo = false,
                typeUnknown = true,
                offline = false,
            ),
        )
    }

    @Test
    fun `a proven-empty counterpart endpoint is off for a week and then tried again`() {
        val now = 1_000_000L
        val offUntil = SongVersionPolicy.counterpartOffUntil(now)
        assertEquals(now + SongVersionPolicy.COUNTERPART_OFF_MS, offUntil)
        assertTrue(SongVersionPolicy.counterpartSuppressed(offUntil, now))
        assertTrue(SongVersionPolicy.counterpartSuppressed(offUntil, offUntil - 1))
        assertFalse(SongVersionPolicy.counterpartSuppressed(offUntil, offUntil))
        assertFalse(SongVersionPolicy.counterpartSuppressed(offUntil, offUntil + 1))
        assertFalse(SongVersionPolicy.counterpartSuppressed(null, now))
    }

    @Test
    fun `only an undecided video or preview downloads its song version`() {
        assertTrue(
            SongVersionPolicy.downloadIsSongVersion(
                isVideo = true,
                typeUnknown = false,
                knownMissing = false,
            ),
        )
        assertTrue(
            SongVersionPolicy.downloadIsSongVersion(
                isVideo = false,
                typeUnknown = true,
                knownMissing = false,
            ),
        )
        assertFalse(
            SongVersionPolicy.downloadIsSongVersion(
                isVideo = true,
                typeUnknown = false,
                knownMissing = true,
            ),
        )
        assertFalse(
            SongVersionPolicy.downloadIsSongVersion(
                isVideo = false,
                typeUnknown = false,
                knownMissing = false,
            ),
        )
    }

    @Test
    fun `the mv sound slack is inclusive and only for unknown provenance`() {
        assertEquals(
            SongVersionPolicy.MV_SOUND_SLACK_MS,
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = 200_000L + SongVersionPolicy.MV_SOUND_SLACK_MS,
                catalogDurationMs = 200_000L,
                isVideo = false,
                localFile = true,
                soundSourceId = null,
            ),
        )
        assertNull(
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = 200_000L + SongVersionPolicy.MV_SOUND_SLACK_MS - 1,
                catalogDurationMs = 200_000L,
                isVideo = false,
                localFile = true,
                soundSourceId = null,
            ),
        )
        assertNull(
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = 210_700L,
                catalogDurationMs = 206_700L,
                isVideo = false,
                localFile = false,
                soundSourceId = null,
            ),
        )
        assertNull(
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = 0L,
                catalogDurationMs = 206_700L,
                isVideo = false,
                localFile = true,
                soundSourceId = null,
            ),
        )
        assertNull(
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = 210_700L,
                catalogDurationMs = 0L,
                isVideo = false,
                localFile = true,
                soundSourceId = null,
            ),
        )
        assertNull(
            SongVersionPolicy.mvSoundDeltaMs(
                fileDurationMs = 190_000L,
                catalogDurationMs = 206_700L,
                isVideo = false,
                localFile = true,
                soundSourceId = null,
            ),
        )
    }

    @Test
    fun `a saved file's use is decided by its recorded sound source`() {
        assertEquals(
            SongVersionPolicy.DownloadedFileUse.PLAY_FILE,
            SongVersionPolicy.downloadedFileUse(
                playId = "song",
                soundSourceId = "song",
                online = true,
            ),
        )
        assertEquals(
            SongVersionPolicy.DownloadedFileUse.STREAM_SONG,
            SongVersionPolicy.downloadedFileUse(
                playId = "song",
                soundSourceId = "video",
                online = true,
            ),
        )
        assertEquals(
            SongVersionPolicy.DownloadedFileUse.PLAY_FILE,
            SongVersionPolicy.downloadedFileUse(
                playId = "song",
                soundSourceId = "video",
                online = false,
            ),
        )
        assertEquals(
            SongVersionPolicy.DownloadedFileUse.UNKNOWN_PLAY_FILE,
            SongVersionPolicy.downloadedFileUse(
                playId = "song",
                soundSourceId = null,
                online = true,
            ),
        )
    }

    @Test
    fun `a legacy file is never adopted as a recording it does not name`() {
        assertTrue(SongVersionPolicy.mayAdoptDownloadedFile("song", "song"))
        assertFalse(SongVersionPolicy.mayAdoptDownloadedFile("song", "video"))
        assertFalse(SongVersionPolicy.mayAdoptDownloadedFile("song", null))
    }
}
