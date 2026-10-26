package com.music.bitchord.download

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A repair pass that is already scanning or downloading must not be cancelled
 * by the next app start, playback transition or verdict. REPLACE did exactly
 * that and could keep a library with several suspects from ever finishing.
 */
class MvSoundRedownloadPolicyTest {

    @Test
    fun `a new schedule queues behind a pass already in flight`() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, MvSoundRedownload.WORK_POLICY)
    }
}
