package com.music.bitchord.playback.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The song check asks for an opening at most once per song per play. A
 * transient failure belongs to that song and that play only: another song may
 * still be fetched, and the same song asks again on the next playback event.
 * A missing cache entry is never a reason to skip the attempt — for a
 * next-prep row it is the normal case.
 */
class SongAudioAttemptPolicyTest {

    @Test
    fun `a failure for one song does not stop another song being attempted`() {
        val attempts = VideoAudio.SongAudioAttempts()
        attempts.noteFailure("failed")

        assertFalse(attempts.mayAttempt("failed"))
        assertTrue(attempts.mayAttempt("other"))
    }

    @Test
    fun `a song with no cache entry still attempts a download`() {
        assertTrue(
            VideoAudio.songAudioMayStart(
                hasEntry = false,
                failedThisPlay = false,
                screenOn = true,
            ),
        )
    }

    @Test
    fun `the same song is attempted once per play and again after the next playback event`() {
        val attempts = VideoAudio.SongAudioAttempts()
        assertTrue(attempts.mayAttempt("song"))
        attempts.noteFailure("song")

        assertFalse("the same play must not retry the failed song", attempts.mayAttempt("song"))
        assertFalse(attempts.mayAttempt("song"))

        attempts.onPlaybackStarted()
        assertTrue("the next playback is a new attempt", attempts.mayAttempt("song"))
    }

    @Test
    fun `screen off keeps the attempt for a later event`() {
        assertFalse(
            VideoAudio.songAudioMayStart(
                hasEntry = false,
                failedThisPlay = false,
                screenOn = false,
            ),
        )
    }
}
