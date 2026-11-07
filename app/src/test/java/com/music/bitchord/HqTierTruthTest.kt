package com.music.bitchord

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TelemetryProvenance
import com.music.bitchord.data.sources.DownloadHealthPolicy
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.ko.KoQualityUpgrade
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the player says about the audio, against what the audio is.
 *
 * 11-07 'Titanium' (LRPGqNeav_M): a saved 16-bit FLAC was playing, the decoder said
 * so, and the song had no label from start to end while its menu read "already best".
 */
class HqTierTruthTest {

    @After
    fun tearDown() {
        NerdStats.forgetLastSession()
    }

    private fun decoded(mime: String, kbps: Int?) = NerdStats.Snapshot(
        mimeType = mime,
        bitrateKbps = kbps,
        sampleRateHz = 44_100,
        channels = 2,
        bitDepth = 16,
        mediaId = "track",
    )

    /** 14:47:40 resolved at 152 kbps; 14:48:10.951 audio/flac from the saved file. */
    @Test
    fun `a saved FLAC moved in behind a stream does not inherit its bitrate`() {
        NerdStats.onStreamPicked("track", 152, "YouTube")

        val bitrate = NerdStats.sourceBitrate("track", decodedLossless = true) { null }

        assertNull(bitrate)
        assertTrue(decoded("audio/flac", bitrate?.first).showsLosslessBadge)
    }

    /** What that figure did: read as a transcode, and the badge went away. */
    @Test
    fun `the stream's figure on a FLAC is what took the badge away`() {
        assertFalse(decoded("audio/flac", 152).showsLosslessBadge)
    }

    @Test
    fun `a lossy claim from the replaced stream does not describe a FLAC either`() {
        NerdStats.onSourceStream("track", StreamFormat("mp4", kbps = 128), "Addon")

        assertNull(NerdStats.sourceBitrate("track", decodedLossless = true) { null })
    }

    @Test
    fun `a lossless decoder still has its real size measured`() {
        NerdStats.onStreamPicked("track", 152, "YouTube")

        assertEquals(
            201 to TelemetryProvenance.MEASURED,
            NerdStats.sourceBitrate("track", decodedLossless = true) { 201 },
        )
        assertFalse("a FLAC measured this small is still a transcode", decoded("audio/flac", 201).showsLosslessBadge)
    }

    @Test
    fun `a lossy decoder keeps the stream it was picked at`() {
        NerdStats.onStreamPicked("track", 152, "YouTube")

        assertEquals(
            152 to TelemetryProvenance.AUTHORITATIVE,
            NerdStats.sourceBitrate("track", decodedLossless = false) { null },
        )
    }

    @Test
    fun `the log names the badge the screen was told to draw`() {
        assertEquals("lossless", decoded("audio/flac", null).badgeName)
        assertEquals("none(suspect lossless)", decoded("audio/flac", 152).badgeName)
        assertEquals("high", decoded("audio/mp4a-latm", 320).badgeName)
        assertEquals("none", decoded("audio/opus", 152).badgeName)
    }

    // ── A saved lossy file ───────────────────────────────────────────────────

    private fun judged(
        tier: HqTier,
        verified: HqTier? = null,
        online: Boolean = true,
        replaces: Boolean = true,
        sound: Boolean? = true,
    ) = DownloadHealthPolicy.judge(
        DownloadHealthPolicy.Input(
            songTitle = "HOT",
            songArtist = "LE SSERAFIM",
            soundAccepted = sound,
            currentTier = tier,
            verifiedTier = verified,
            online = online,
            replacesSavedCopies = replaces,
        ),
    )

    /** 14:39:13 cCkAcVOS3ig `result=ok action=none tier=NORMAL`, and Opus for good. */
    @Test
    fun `a sound lossy file asks for one search and is still healthy`() {
        val decision = judged(HqTier.NORMAL)

        assertEquals(DownloadHealthPolicy.Verdict.OK, decision.verdict)
        assertEquals(DownloadHealthPolicy.Action.UPGRADE, decision.action)
        assertEquals(DownloadHealthPolicy.Action.UPGRADE, judged(HqTier.HIGH_LOSSY).action)
    }

    @Test
    fun `a lossless file does not`() {
        assertEquals(DownloadHealthPolicy.Action.NONE, judged(HqTier.LOSSLESS_16).action)
        assertEquals(DownloadHealthPolicy.Action.NONE, judged(HqTier.HI_RES_24_44).action)
    }

    @Test
    fun `nothing is asked offline or with the switch off`() {
        assertEquals(DownloadHealthPolicy.Action.NONE, judged(HqTier.NORMAL, online = false).action)
        assertEquals(DownloadHealthPolicy.Action.NONE, judged(HqTier.NORMAL, replaces = false).action)
    }

    @Test
    fun `a wrong recording is still condemned before quality comes up`() {
        assertEquals(
            DownloadHealthPolicy.Verdict.WRONG_RECORDING,
            judged(HqTier.NORMAL, sound = false).verdict,
        )
    }

    // ── What the automatic swap takes ────────────────────────────────────────

    @Test
    fun `the automatic swap takes lossless and a stated high rate only`() {
        assertTrue(KoQualityUpgrade.automaticSwapTakes(StreamFormat("flac")))
        assertTrue(KoQualityUpgrade.automaticSwapTakes(StreamFormat("mp4", kbps = 320)))
        assertFalse(KoQualityUpgrade.automaticSwapTakes(StreamFormat("mp4", kbps = 160)))
        assertFalse(KoQualityUpgrade.automaticSwapTakes(StreamFormat("dolby-atmos")))
        assertFalse(KoQualityUpgrade.automaticSwapTakes(null))
    }

    @Test
    fun `a guessed rate and a demoted FLAC are not a high rate`() {
        assertFalse(KoQualityUpgrade.automaticSwapTakes(StreamFormat("mp3", kbps = 320, kbpsInferred = true)))
        assertFalse(KoQualityUpgrade.automaticSwapTakes(StreamFormat("flac", kbps = 280)))
    }

    // ── Why nothing looked ───────────────────────────────────────────────────

    private fun reason(
        hasVideoId: Boolean = true,
        musicVideo: Boolean = false,
        answered: Boolean = false,
    ) = KoQualityUpgrade.noLookReason(
        hasUri = true,
        hasVideoId = hasVideoId,
        musicVideo = musicVideo,
        marked = false,
        answered = answered,
        broke = false,
        retryWindow = false,
        canSubstitute = true,
    )

    @Test
    fun `a play with no look says which kind it was`() {
        assertEquals("plays-a-file", reason(hasVideoId = false))
        assertEquals("video-row", reason(musicVideo = true))
        assertEquals("already-answered", reason(answered = true))
    }

    @Test
    fun `one inconclusive look gets one retry`() {
        assertTrue(KoQualityUpgrade.takeRetry("retry-once"))
        assertFalse(KoQualityUpgrade.takeRetry("retry-once"))

        KoQualityUpgrade.onPlaybackStarted("retry-once")

        assertTrue(KoQualityUpgrade.takeRetry("retry-once"))
        KoQualityUpgrade.onPlaybackStarted("retry-once")
    }
}
