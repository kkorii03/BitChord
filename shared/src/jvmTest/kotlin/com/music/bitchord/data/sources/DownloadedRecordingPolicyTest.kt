package com.music.bitchord.data.sources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedRecordingPolicyTest {

    @Test
    fun unverifiedOnlineDownloadDoesNotStartOnTheFile() {
        assertFalse(
            DownloadedRecordingPolicy.fileFirst(
                downloaded = true,
                wrongRecording = false,
                verified = false,
                offline = false,
            ),
        )
    }

    @Test
    fun verifiedDownloadStillStartsOnTheFile() {
        assertTrue(
            DownloadedRecordingPolicy.fileFirst(
                downloaded = true,
                wrongRecording = false,
                verified = true,
                offline = false,
            ),
        )
    }

    @Test
    fun unverifiedDownloadKeepsPlayingOffline() {
        assertTrue(
            DownloadedRecordingPolicy.fileFirst(
                downloaded = true,
                wrongRecording = false,
                verified = false,
                offline = true,
            ),
        )
    }

    @Test
    fun wrongRecordingNeverPlaysEvenWhenOfflineOrVerified() {
        for (verified in listOf(true, false)) {
            for (offline in listOf(true, false)) {
                assertFalse(
                    DownloadedRecordingPolicy.fileFirst(
                        downloaded = true,
                        wrongRecording = true,
                        verified = verified,
                        offline = offline,
                    ),
                )
            }
        }
    }

    @Test
    fun listenerOwnLocalFileIsNotGated() {
        assertTrue(
            DownloadedRecordingPolicy.fileFirst(
                downloaded = false,
                wrongRecording = false,
                verified = false,
                offline = false,
            ),
        )
    }

    @Test
    fun onlyAFreshAcceptanceMarksTheRecordingVerified() {
        assertTrue(
            DownloadedRecordingPolicy.recordsVerified(
                freshSoundAccepted = true,
                verdict = DownloadHealthPolicy.Verdict.OK,
            ),
        )
        assertFalse(
            DownloadedRecordingPolicy.recordsVerified(
                freshSoundAccepted = false,
                verdict = DownloadHealthPolicy.Verdict.OK,
            ),
        )
        assertFalse(
            DownloadedRecordingPolicy.recordsVerified(
                freshSoundAccepted = true,
                verdict = DownloadHealthPolicy.Verdict.WRONG_RECORDING,
            ),
        )
        assertFalse(
            DownloadedRecordingPolicy.recordsVerified(
                freshSoundAccepted = true,
                verdict = DownloadHealthPolicy.Verdict.ARRANGEMENT,
            ),
        )
    }
}
