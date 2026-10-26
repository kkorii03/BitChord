package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether a downloaded file is a music video's sound, including records written
 * before the upload type was stored.
 */
class DownloadedMvPolicyTest {

    @Test
    fun `a legacy official video download is an mv from its title alone`() {
        assertTrue(
            DownloadedMvPolicy.isMvSound(
                rowVideoId = "QYh6mYIJG2Y",
                title = "7 rings (Official Video)",
                fileDurationMs = null,
                storedIsVideo = null,
                storedTypeUnknown = false,
                soundSourceVideoId = "QYh6mYIJG2Y",
                catalogVideoId = null,
                catalogDurationMs = null,
            ),
        )
    }

    @Test
    fun `a file twenty seconds longer than the catalogue song is an mv`() {
        assertTrue(
            DownloadedMvPolicy.isMvSound(
                rowVideoId = "video-id",
                title = "When I Was Your Man",
                fileDurationMs = 200_000L + 20_000L,
                storedIsVideo = null,
                storedTypeUnknown = false,
                soundSourceVideoId = null,
                catalogVideoId = "song-id",
                catalogDurationMs = 200_000L,
            ),
        )
    }

    @Test
    fun `a downloaded song version stays the song`() {
        assertFalse(
            DownloadedMvPolicy.isMvSound(
                rowVideoId = "song-id",
                title = "When I Was Your Man",
                fileDurationMs = 220_000L,
                storedIsVideo = false,
                storedTypeUnknown = false,
                soundSourceVideoId = "song-id",
                catalogVideoId = "song-id",
                catalogDurationMs = 200_000L,
            ),
        )
    }

    @Test
    fun `a cover with no catalogue release is not an mv`() {
        assertFalse(
            DownloadedMvPolicy.isMvSound(
                rowVideoId = "cover-id",
                title = "Shape of You (Cover)",
                fileDurationMs = 260_000L,
                storedIsVideo = null,
                storedTypeUnknown = false,
                soundSourceVideoId = "cover-id",
                catalogVideoId = null,
                catalogDurationMs = null,
            ),
        )
    }

    @Test
    fun `every documented video marker is recognised`() {
        listOf(
            "Song (Official Video)",
            "Song Official Music Video",
            "Song (Official MV)",
            "Song M/V",
            "Song [MV]",
            "Song MV",
            "Song Music Video",
        ).forEach { title ->
            assertTrue(DownloadedMvPolicy.hasMvTitleMarker(title), title)
        }
        assertFalse(DownloadedMvPolicy.hasMvTitleMarker("Take Cover"))
        assertFalse(DownloadedMvPolicy.hasMvTitleMarker("Moves"))
    }

    @Test
    fun `a stored video or unknown type alone is not conclusive`() {
        assertFalse(
            DownloadedMvPolicy.isMvSound(
                rowVideoId = "id",
                title = "Song",
                fileDurationMs = null,
                storedIsVideo = true,
                storedTypeUnknown = false,
                soundSourceVideoId = "id",
                catalogVideoId = null,
                catalogDurationMs = null,
            ),
        )
        assertFalse(
            DownloadedMvPolicy.isMvSound(
                rowVideoId = "id",
                title = "Song",
                fileDurationMs = null,
                storedIsVideo = null,
                storedTypeUnknown = true,
                soundSourceVideoId = "id",
                catalogVideoId = null,
                catalogDurationMs = null,
            ),
        )
    }

    @Test
    fun `a file recorded from another upload is left to the provenance rule`() {
        assertFalse(
            DownloadedMvPolicy.isMvSound(
                rowVideoId = "video-id",
                title = "Song (Official Video)",
                fileDurationMs = 220_000L,
                storedIsVideo = null,
                storedTypeUnknown = false,
                soundSourceVideoId = "song-id",
                catalogVideoId = "song-id",
                catalogDurationMs = 200_000L,
            ),
        )
    }

    @Test
    fun `a re-download keeps the recorded song upload type`() {
        assertTrue(
            DownloadedMvPolicy.keepsStoredUploadType(
                storedIsVideo = false,
                storedTypeUnknown = false,
                incomingIsVideo = false,
                incomingTypeUnknown = true,
            ),
        )
    }

    @Test
    fun `a re-download keeps the recorded video upload type`() {
        assertTrue(
            DownloadedMvPolicy.keepsStoredUploadType(
                storedIsVideo = true,
                storedTypeUnknown = false,
                incomingIsVideo = false,
                incomingTypeUnknown = true,
            ),
        )
    }

    @Test
    fun `a definite incoming type is written even over an older record`() {
        assertFalse(
            DownloadedMvPolicy.keepsStoredUploadType(
                storedIsVideo = false,
                storedTypeUnknown = false,
                incomingIsVideo = false,
                incomingTypeUnknown = false,
            ),
        )
        assertFalse(
            DownloadedMvPolicy.keepsStoredUploadType(
                storedIsVideo = false,
                storedTypeUnknown = false,
                incomingIsVideo = true,
                incomingTypeUnknown = false,
            ),
        )
    }

    @Test
    fun `a legacy record with no type is not made definite by a repair placeholder`() {
        assertFalse(
            DownloadedMvPolicy.keepsStoredUploadType(
                storedIsVideo = null,
                storedTypeUnknown = false,
                incomingIsVideo = false,
                incomingTypeUnknown = true,
            ),
        )
    }

    @Test
    fun `the downloads scan leaves a recognized song version alone`() {
        assertNull(
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "song-id",
                title = "When I Was Your Man",
                fileDurationMs = 220_000L,
                storedIsVideo = false,
                storedTypeUnknown = false,
                soundSourceVideoId = "song-id",
                catalogDurationMs = 200_000L,
            ),
        )
    }

    @Test
    fun `the downloads scan keeps a recorded mv whose title marks it`() {
        assertEquals(
            0L,
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "video-id",
                title = "Song (Official Video)",
                fileDurationMs = 200_000L,
                storedIsVideo = true,
                storedTypeUnknown = false,
                soundSourceVideoId = "video-id",
                catalogDurationMs = 200_000L,
            ),
        )
    }

    @Test
    fun `the downloads scan catches a song id whose bytes came from another upload`() {
        assertEquals(
            20_000L,
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "song-id",
                title = "Song",
                fileDurationMs = 220_000L,
                storedIsVideo = false,
                storedTypeUnknown = false,
                soundSourceVideoId = "video-id",
                catalogDurationMs = 200_000L,
            ),
        )
    }

    @Test
    fun `the downloads scan still measures a legacy file by its length`() {
        assertEquals(
            20_000L,
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "video-id",
                title = "Song",
                fileDurationMs = 220_000L,
                storedIsVideo = null,
                storedTypeUnknown = false,
                soundSourceVideoId = null,
                catalogDurationMs = 200_000L,
            ),
        )
        assertNull(
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "video-id",
                title = "Song",
                fileDurationMs = 201_000L,
                storedIsVideo = null,
                storedTypeUnknown = false,
                soundSourceVideoId = null,
                catalogDurationMs = 200_000L,
            ),
        )
    }

    @Test
    fun `the downloads scan keeps its two and a half second legacy threshold`() {
        assertEquals(
            2_600L,
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "id",
                title = "Song",
                fileDurationMs = 202_600L,
                storedIsVideo = null,
                storedTypeUnknown = false,
                soundSourceVideoId = null,
                catalogDurationMs = 200_000L,
            ),
        )
        assertNull(
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "id",
                title = "Song",
                fileDurationMs = 202_400L,
                storedIsVideo = null,
                storedTypeUnknown = false,
                soundSourceVideoId = null,
                catalogDurationMs = 200_000L,
            ),
        )
    }

    @Test
    fun `a downloaded cover video is not an mv sound from its byline`() {
        assertNull(
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "dance-hall-cover",
                title = "ダンスホール - Dance Hall",
                fileDurationMs = 203_000L,
                storedIsVideo = true,
                storedTypeUnknown = false,
                soundSourceVideoId = "dance-hall-cover",
                catalogDurationMs = 203_000L,
                artist = "Mrs. GREEN APPLE · 오화요 커버",
            ),
        )
        assertNull(
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "yahwa-cover",
                title = "야화",
                fileDurationMs = 214_000L,
                storedIsVideo = true,
                storedTypeUnknown = false,
                soundSourceVideoId = "yahwa-cover",
                catalogDurationMs = 214_000L,
                artist = "안예은 · 오화요 커버",
            ),
        )
    }

    @Test
    fun `a normal song with an unknown stored type and equal length is not an mv`() {
        assertNull(
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "youngblood",
                title = "Youngblood",
                fileDurationMs = 203_000L,
                storedIsVideo = false,
                storedTypeUnknown = true,
                soundSourceVideoId = "youngblood",
                catalogDurationMs = 203_000L,
                artist = "5 Seconds of Summer",
            ),
        )
    }

    @Test
    fun `an official video title is still an mv`() {
        assertEquals(
            0L,
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "youngblood-video",
                title = "Youngblood (Official Video)",
                fileDurationMs = 224_000L,
                storedIsVideo = true,
                storedTypeUnknown = false,
                soundSourceVideoId = "youngblood-video",
                catalogDurationMs = 224_000L,
                artist = "5 Seconds of Summer",
            ),
        )
    }

    @Test
    fun `the legacy length rule still catches that's what i like`() {
        assertEquals(
            4_000L,
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "thats-what-i-like-video",
                title = "That's What I Like",
                fileDurationMs = 210_700L,
                storedIsVideo = null,
                storedTypeUnknown = false,
                soundSourceVideoId = null,
                catalogDurationMs = 206_700L,
                artist = "Bruno Mars",
            ),
        )
    }

    @Test
    fun `a different source recorded as a video is an mv even at equal length`() {
        assertEquals(
            0L,
            DownloadedMvPolicy.mvSoundSuspectDeltaMs(
                rowVideoId = "song-id",
                title = "Song",
                fileDurationMs = 200_000L,
                storedIsVideo = false,
                storedTypeUnknown = false,
                soundSourceVideoId = "video-id",
                catalogDurationMs = 200_000L,
                artist = "Artist",
                sourceRecordedAsMv = true,
            ),
        )
    }
}
