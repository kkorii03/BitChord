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
        soundCached: Boolean = false,
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
        soundCached = soundCached,
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
    fun `a cached acceptance does not hide a runtime mismatch`() {
        val decision = DownloadHealthPolicy.judge(
            input(
                soundAccepted = true,
                soundCached = true,
                fileMs = 200_000L + DownloadHealthPolicy.DURATION_TOLERANCE_MS + 1,
            ),
        )
        assertEquals(DownloadHealthPolicy.Verdict.WRONG_RECORDING, decision.verdict)
    }

    @Test
    fun `a cached acceptance does not hide an arrangement marker`() {
        val decision = DownloadHealthPolicy.judge(
            input(
                soundAccepted = true,
                soundCached = true,
                sourceTitle = "Song (Piano Cover)",
                sourceArtist = "Piano Dream",
            ),
        )
        assertEquals(DownloadHealthPolicy.Verdict.ARRANGEMENT, decision.verdict)
    }

    /**
     * Phone 11-07, Counting Stars: `hq-verify: downloaded file download match=100%
     * offset=9187 -> accepted`, then `download-check: _GWKkqNoyEA result=ok`, and the
     * player moved onto a 344 s file under a 258 s album row. The comparison hears the
     * opening ninety seconds; it cannot speak for the other four minutes.
     */
    @Test
    fun `a fresh acceptance does not hide a cut far longer than the song`() {
        val online = DownloadHealthPolicy.judge(
            input(soundAccepted = true, catalogMs = 258_000L, fileMs = 343_666L),
        )
        assertEquals(DownloadHealthPolicy.Verdict.WRONG_RECORDING, online.verdict)
        assertEquals(DownloadHealthPolicy.Action.STREAM, online.action)

        val offline = DownloadHealthPolicy.judge(
            input(soundAccepted = true, catalogMs = 258_000L, fileMs = 343_666L, online = false),
        )
        assertEquals(DownloadHealthPolicy.Action.REDOWNLOAD, offline.action)
    }

    /** What the same file meets on its next play: the stored verdict of the first. */
    @Test
    fun `the replay of that cut is condemned on its stored acceptance too`() {
        val decision = DownloadHealthPolicy.judge(
            input(soundAccepted = true, soundCached = true, catalogMs = 258_000L, fileMs = 343_666L),
        )
        assertEquals(DownloadHealthPolicy.Verdict.WRONG_RECORDING, decision.verdict)
    }

    @Test
    fun `a fresh acceptance does not hide a clip of the song`() {
        val decision = DownloadHealthPolicy.judge(
            input(soundAccepted = true, catalogMs = 180_000L, fileMs = 30_000L),
        )
        assertEquals(DownloadHealthPolicy.Verdict.WRONG_RECORDING, decision.verdict)
    }

    /** A fade a service trims differently is still the recording the audio proved. */
    @Test
    fun `a fresh acceptance keeps a file inside the matcher's window`() {
        val limitMs = TrackMatcher.DURATION_LIMIT_SEC * 1_000L
        listOf(200_000L + limitMs, 200_000L - limitMs).forEach { fileMs ->
            val decision = DownloadHealthPolicy.judge(
                input(soundAccepted = true, catalogMs = 200_000L, fileMs = fileMs),
            )
            assertEquals(fileMs.toString(), DownloadHealthPolicy.Verdict.OK, decision.verdict)
        }
    }

    @Test
    fun `a fresh acceptance with no runtime to compare stays accepted`() {
        listOf(null to 343_666L, 258_000L to null, 258_000L to 0L).forEach { (catalogMs, fileMs) ->
            val decision = DownloadHealthPolicy.judge(
                input(soundAccepted = true, catalogMs = catalogMs, fileMs = fileMs),
            )
            assertEquals(DownloadHealthPolicy.Verdict.OK, decision.verdict)
        }
    }

    /** Chroma follows the harmony, so a stage take in the same key can clear the bar. */
    @Test
    fun `a fresh acceptance does not hide a source row naming a live take`() {
        listOf(
            "Song (Live)",
            "Song (Live at Wembley)",
            "Song - Live",
            "Song (Concert Version)",
            "Song [Spotify Sessions]",
            "Song (MTV Unplugged)",
            "Song (라이브)",
        ).forEach { title ->
            val decision = DownloadHealthPolicy.judge(input(soundAccepted = true, sourceTitle = title))
            assertEquals(title, DownloadHealthPolicy.Verdict.ARRANGEMENT, decision.verdict)
            assertEquals(title, DownloadHealthPolicy.Action.STREAM, decision.action)
        }
    }

    @Test
    fun `live in the song's own name is not a live take`() {
        listOf(
            Triple("Let Me Live", "Let Me Live", "Queen"),
            Triple("Song (Live)", "Song (Live)", "Artist"),
        ).forEach { (sourceTitle, songTitle, songArtist) ->
            val decision = DownloadHealthPolicy.judge(
                input(
                    soundAccepted = true,
                    sourceTitle = sourceTitle,
                    songTitle = songTitle,
                    songArtist = songArtist,
                ),
            )
            assertEquals(sourceTitle, DownloadHealthPolicy.Verdict.OK, decision.verdict)
        }
    }

    @Test
    fun `a file replaced during verification is not marked wrong`() {
        assertFalse(
            DownloadHealthPolicy.shouldMarkWrong(
                currentUri = "file:///new.flac",
                inspectedUri = "file:///old.flac",
                alreadyWrong = false,
            ),
        )
        assertTrue(
            DownloadHealthPolicy.shouldMarkWrong(
                currentUri = "file:///old.flac",
                inspectedUri = "file:///old.flac",
                alreadyWrong = false,
            ),
        )
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

    @Test
    fun `a repair request waits out its cooldown`() {
        val now = 1_000_000_000L
        assertTrue(DownloadHealthPolicy.mayRequestRepair(lastRequestMs = null, nowMs = now))
        assertFalse(DownloadHealthPolicy.mayRequestRepair(lastRequestMs = now - 1_000L, nowMs = now))
        assertTrue(
            DownloadHealthPolicy.mayRequestRepair(
                lastRequestMs = now - DownloadHealthPolicy.REPAIR_COOLDOWN_MS,
                nowMs = now,
            ),
        )
    }
}
