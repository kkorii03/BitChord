package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A downloaded song's own file is re-checked in next-prep only when the file
 * lyrics are what will be shown and the re-check switch allows the override.
 * A video's file is timed by the video path; a substitute is another recording.
 */
class NextPrepDownloadRecheckStepTest {

    @Test
    fun downloadedSongFileWithTheSwitchOnIsRechecked() {
        assertTrue(
            NextPrepPolicy.downloadRecheckStepWanted(
                fromFile = true,
                isVideo = false,
                substituted = false,
                recheckAllowed = true,
            ),
        )
    }

    @Test
    fun theRecheckSwitchOffKeepsTheFileUntouched() {
        assertFalse(
            NextPrepPolicy.downloadRecheckStepWanted(
                fromFile = true,
                isVideo = false,
                substituted = false,
                recheckAllowed = false,
            ),
        )
    }

    @Test
    fun aStreamVideoOrSubstituteIsNotARecheckStep() {
        assertFalse(
            NextPrepPolicy.downloadRecheckStepWanted(
                fromFile = false,
                isVideo = false,
                substituted = false,
                recheckAllowed = true,
            ),
        )
        assertFalse(
            NextPrepPolicy.downloadRecheckStepWanted(
                fromFile = true,
                isVideo = true,
                substituted = false,
                recheckAllowed = true,
            ),
        )
        assertFalse(
            NextPrepPolicy.downloadRecheckStepWanted(
                fromFile = true,
                isVideo = false,
                substituted = true,
                recheckAllowed = true,
            ),
        )
    }
}
