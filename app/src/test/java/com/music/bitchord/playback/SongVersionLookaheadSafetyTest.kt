package com.music.bitchord.playback

import com.music.bitchord.data.net.SongVersionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upcoming-row look-ahead must not open a saved file for a length it
 * already knows, and must not replace a row that changed while the file was
 * being read off the main thread.
 *
 * Both reads suspend (the saved URI and the container metadata), so a queue
 * edit can land between resolving the version and applying it.
 *
 * The music-only memory lives here too: it is the same question of a verdict
 * that outlived the queue it was taken in.
 */
class SongVersionLookaheadSafetyTest {

    @Test
    fun `a recorded provenance does not need a file read`() {
        assertFalse(
            SongVersionPolicy.needsFileDurationForReplacement(
                soundSourceId = "song",
                mvSoundSuspectMs = null,
                catalogDurationMs = 240_000L,
            ),
        )
    }

    @Test
    fun `a remembered video-sound verdict does not need a file read`() {
        assertFalse(
            SongVersionPolicy.needsFileDurationForReplacement(
                soundSourceId = null,
                mvSoundSuspectMs = 0L,
                catalogDurationMs = 240_000L,
            ),
        )
    }

    @Test
    fun `a legacy file with a catalogue length is measured`() {
        assertTrue(
            SongVersionPolicy.needsFileDurationForReplacement(
                soundSourceId = null,
                mvSoundSuspectMs = null,
                catalogDurationMs = 240_000L,
            ),
        )
    }

    @Test
    fun `a row with no catalogue length is not measured`() {
        assertFalse(
            SongVersionPolicy.needsFileDurationForReplacement(
                soundSourceId = null,
                mvSoundSuspectMs = null,
                catalogDurationMs = null,
            ),
        )
    }

    @Test
    fun `the same row at the same index may be replaced`() {
        assertTrue(
            SongVersionPolicy.replacementRowStillMatches(
                expectedIndex = 3,
                expectedMediaId = "video",
                itemCount = 5,
                actualMediaId = "video",
            ),
        )
    }

    @Test
    fun `a row that moved out of the queue is not replaced`() {
        assertFalse(
            SongVersionPolicy.replacementRowStillMatches(
                expectedIndex = 3,
                expectedMediaId = "video",
                itemCount = 3,
                actualMediaId = "video",
            ),
        )
    }

    @Test
    fun `another entry moved into the index is not replaced`() {
        assertFalse(
            SongVersionPolicy.replacementRowStillMatches(
                expectedIndex = 3,
                expectedMediaId = "video",
                itemCount = 5,
                actualMediaId = "other",
            ),
        )
    }

    @Test
    fun `a row that started playing during the read is not replaced`() {
        assertFalse(
            SongVersionPolicy.replacementRowStillMatches(
                expectedIndex = 3,
                expectedMediaId = "video",
                itemCount = 5,
                actualMediaId = "video",
                playingIndex = 3,
            ),
        )
    }

    @Test
    fun `a row still ahead of the playhead may be replaced`() {
        assertTrue(
            SongVersionPolicy.replacementRowStillMatches(
                expectedIndex = 3,
                expectedMediaId = "video",
                itemCount = 5,
                actualMediaId = "video",
                playingIndex = 1,
            ),
        )
    }

    // --- music-only's once-per-song memory ----------------------------------

    @Test
    fun `a song music-only refused is not refused again`() {
        val skipped = SkippedOfficialMv()
        assertFalse(skipped.contains("omv"))
        skipped.mark("omv")
        assertTrue(skipped.contains("omv"))
        assertFalse(skipped.contains("other"))
    }

    @Test
    fun `a new queue or a music-only change forgets the refusals`() {
        val skipped = SkippedOfficialMv()
        skipped.mark("omv")
        skipped.clear()
        assertFalse(skipped.contains("omv"))
    }
}
