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

    @Test
    fun aStoredCheckPromotesOnlyWhenItRecordedAFreshAcceptance() {
        assertFalse(
            DownloadedRecordingPolicy.promotesCheckedFile(
                alreadyVerified = false,
                freshAcceptanceRecorded = false,
            ),
        )
        assertTrue(
            DownloadedRecordingPolicy.promotesCheckedFile(
                alreadyVerified = false,
                freshAcceptanceRecorded = true,
            ),
        )
        assertFalse(
            DownloadedRecordingPolicy.promotesCheckedFile(
                alreadyVerified = true,
                freshAcceptanceRecorded = true,
            ),
        )
    }

    @Test
    fun aProbedContainerMustMatchTheNameTheFileIsSavedUnder() {
        assertTrue(DownloadedRecordingPolicy.containerMatches("flac", "flac"))
        assertFalse(DownloadedRecordingPolicy.containerMatches("flac", "m4a"))
        assertFalse(DownloadedRecordingPolicy.containerMatches("wav", "mp3"))
        // A manifest has no single container yet; the assembler names it.
        assertTrue(DownloadedRecordingPolicy.containerMatches("flac", null))
    }
}
