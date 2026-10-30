package com.music.bitchord.download

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `leaving the app leaves a running repair alone`() {
        assertFalse(MvSoundRedownload.cancelBeforeStart(WorkInfo.State.RUNNING))
    }

    @Test
    fun `leaving the app drops work that has not started`() {
        assertTrue(MvSoundRedownload.cancelBeforeStart(WorkInfo.State.ENQUEUED))
        assertTrue(MvSoundRedownload.cancelBeforeStart(WorkInfo.State.BLOCKED))
    }

    @Test
    fun `finished work is not cancelled again`() {
        listOf(
            WorkInfo.State.SUCCEEDED,
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED,
        ).forEach { assertFalse(it.name, MvSoundRedownload.cancelBeforeStart(it)) }
    }
}
