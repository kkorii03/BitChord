package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WDYM 11-18: a video row downloaded under its own id (DK_0jXPuIr0) holds the
 * MV's sound. Once its song version is found, the file must be queued for the
 * MV-sound repair; [SongVersionPolicy.verifiesUnknownDownload] leaves known
 * video rows out, so nothing ever queued it.
 */
class DownloadedVideoSoundRepairTest {

    private fun repairs(
        downloaded: Boolean = true,
        isVideo: Boolean? = true,
        isCover: Boolean = false,
        soundSourceId: String? = "DK_0jXPuIr0",
        resolvedVideoId: String = "FCGb0pw_wPU",
        resolvedAlreadySaved: Boolean = false,
    ) = SongVersionPolicy.repairsDownloadedVideoSound(
        downloaded = downloaded,
        isVideo = isVideo,
        isCover = isCover,
        rowVideoId = "DK_0jXPuIr0",
        soundSourceId = soundSourceId,
        resolvedVideoId = resolvedVideoId,
        resolvedAlreadySaved = resolvedAlreadySaved,
    )

    @Test
    fun `an own-id video download whose song version was found is repaired`() {
        assertTrue(repairs())
        assertTrue(repairs(soundSourceId = null))
        // The case the old rule missed: known video, own provenance.
        assertFalse(
            SongVersionPolicy.verifiesUnknownDownload(
                localFile = true,
                downloaded = true,
                provenanceKnown = true,
                isVideo = true,
                typeUnknown = false,
                offline = false,
                provenanceIsOwnId = true,
            ),
        )
    }

    @Test
    fun `nothing else is repaired`() {
        assertFalse(repairs(downloaded = false), "a streamed row has no file")
        assertFalse(repairs(isVideo = false), "a song row is left to its own rules")
        assertFalse(repairs(isVideo = null), "a legacy row is verifiesUnknownDownload's case")
        assertFalse(repairs(isCover = true), "a cover is the listener's chosen performance")
        assertFalse(repairs(soundSourceId = "FCGb0pw_wPU"), "the bytes already came from another upload")
        assertFalse(repairs(resolvedVideoId = "DK_0jXPuIr0"), "no different song version")
        assertFalse(repairs(resolvedVideoId = ""), "no song version")
        assertFalse(repairs(resolvedAlreadySaved = true), "the song is already on the device")
    }
}
