package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHealthPolicyTest {

    private fun input(
        soundAccepted: Boolean? = null,
        catalogMs: Long? = 200_000L,
        fileMs: Long? = 200_000L,
        sourceTitle: String? = null,
        sourceArtist: String? = null,
        songTitle: String = "Song",
        songArtist: String = "Artist",
        current: HqTier? = HqTier.NORMAL,
        verified: HqTier? = null,
        online: Boolean = true,
    ) = DownloadHealthPolicy.Input(
        songTitle = songTitle,
        songArtist = songArtist,
        catalogDurationMs = catalogMs,
        fileDurationMs = fileMs,
        sourceTitle = sourceTitle,
        sourceArtist = sourceArtist,
        soundAccepted = soundAccepted,
        currentTier = current,
        verifiedTier = verified,
        online = online,
    )

    @Test
    fun `a matching recording at the current rung is healthy`() {
        val decision = DownloadHealthPolicy.judge(input(soundAccepted = true))
        assertEquals(DownloadHealthPolicy.Verdict.OK, decision.verdict)
        assertEquals(DownloadHealthPolicy.Action.NONE, decision.action)
    }

    @Test
    fun `a rejected sound comparison streams and repairs`() {
        val online = DownloadHealthPolicy.judge(input(soundAccepted = false))
        assertEquals(DownloadHealthPolicy.Verdict.WRONG_RECORDING, online.verdict)
        assertEquals(DownloadHealthPolicy.Action.STREAM, online.action)

        val offline = DownloadHealthPolicy.judge(input(soundAccepted = false, online = false))
        assertEquals(DownloadHealthPolicy.Action.REDOWNLOAD, offline.action)
    }

    @Test
    fun `a source arrangement marker the song does not carry is an arrangement`() {
        val decision = DownloadHealthPolicy.judge(
            input(sourceTitle = "Song (Piano Cover)", sourceArtist = "Piano Dream"),
        )
        assertEquals(DownloadHealthPolicy.Verdict.ARRANGEMENT, decision.verdict)
        assertEquals(DownloadHealthPolicy.Action.STREAM, decision.action)
    }

    @Test
    fun `the arrangement vocabulary covers the download markers`() {
        listOf(
            "Song (Instrumental)",
            "Song (Karaoke Version)",
            "Song 노래방",
            "Song (8-bit)",
            "Song (Lofi)",
            "Song (Remix)",
            "Song (MR)",
            "Song (inst)",
            "Song 피아노",
        ).forEach { title ->
            assertTrue(title, DownloadHealthPolicy.isArrangement(title, null, "Song", "Artist"))
        }
    }

    @Test
    fun `an honorific Mr is not the MR backing-track marker`() {
        assertFalse(
            DownloadHealthPolicy.isArrangement(
                sourceTitle = "To Be With You",
                sourceArtist = "Mr. Big",
                songTitle = "To Be With You",
                songArtist = "Mr. Big",
            ),
        )
        assertTrue(
            DownloadHealthPolicy.isArrangement("Song (MR)", null, "Song", "Artist"),
        )
    }

    @Test
    fun `a marker the song itself carries is not an arrangement`() {
        assertFalse(
            DownloadHealthPolicy.isArrangement(
                sourceTitle = "Cover Me",
                sourceArtist = "Cover Me",
                songTitle = "Cover Me",
                songArtist = "Cover Me",
            ),
        )
        val decision = DownloadHealthPolicy.judge(
            input(
                songTitle = "Cover Me",
                songArtist = "Cover Me",
                sourceTitle = "Cover Me",
                sourceArtist = "Cover Me",
            ),
        )
        assertEquals(DownloadHealthPolicy.Verdict.OK, decision.verdict)
    }

    @Test
    fun `an accepted comparison outranks a title marker`() {
        val decision = DownloadHealthPolicy.judge(
            input(soundAccepted = true, sourceTitle = "Song (Remix)"),
        )
        assertEquals(DownloadHealthPolicy.Verdict.OK, decision.verdict)
    }

    @Test
    fun `a runtime far from the catalogue is another recording`() {
        val decision = DownloadHealthPolicy.judge(
            input(fileMs = 200_000L + DownloadHealthPolicy.DURATION_TOLERANCE_MS + 1),
        )
        assertEquals(DownloadHealthPolicy.Verdict.WRONG_RECORDING, decision.verdict)
    }

    @Test
    fun `a runtime inside the tolerance is not evidence`() {
        val decision = DownloadHealthPolicy.judge(
            input(fileMs = 200_000L + DownloadHealthPolicy.DURATION_TOLERANCE_MS),
        )
        assertEquals(DownloadHealthPolicy.Verdict.OK, decision.verdict)
    }

    @Test
    fun `a missing runtime is not evidence either`() {
        val decision = DownloadHealthPolicy.judge(input(catalogMs = null, fileMs = 12_000L))
        assertEquals(DownloadHealthPolicy.Verdict.OK, decision.verdict)
    }

    @Test
    fun `a verified higher rung asks for an upgrade`() {
        val decision = DownloadHealthPolicy.judge(
            input(soundAccepted = true, current = HqTier.NORMAL, verified = HqTier.LOSSLESS_16),
        )
        assertEquals(DownloadHealthPolicy.Verdict.LOWER_QUALITY, decision.verdict)
        assertEquals(DownloadHealthPolicy.Action.UPGRADE, decision.action)
    }

    @Test
    fun `an equal or lower verified rung is not an upgrade`() {
        listOf(HqTier.NORMAL, HqTier.HIGH_LOSSY).forEach { verified ->
            val decision = DownloadHealthPolicy.judge(
                input(
                    soundAccepted = true,
                    current = HqTier.HIGH_LOSSY,
                    verified = verified,
                ),
            )
            assertEquals(verified.name, DownloadHealthPolicy.Verdict.OK, decision.verdict)
        }
    }

    @Test
    fun `a wrong recording is repaired before quality is considered`() {
        val decision = DownloadHealthPolicy.judge(
            input(soundAccepted = false, verified = HqTier.HI_RES_24_88),
        )
        assertEquals(DownloadHealthPolicy.Verdict.WRONG_RECORDING, decision.verdict)
        assertEquals(DownloadHealthPolicy.Action.STREAM, decision.action)
    }
}
