package com.music.bitchord.ko

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.model.Song
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone 11-19 (vc215): on LTE (metered, unlimited plan, cellular quality Best)
 * the direct lead, hq-ahead and download-check ahead never ran, though the
 * song itself then played as a 24-bit FLAC over the same LTE. On a metered
 * network they now follow the cellular quality setting: lossless runs them,
 * below lossless keeps them off, and saving power still stops them.
 *
 * Old code: every metered case below at a lossless ceiling answers false.
 */
class KoHiResPrefetchTest {

    private val wifi = AppSettings.audioQualityWifi.value
    private val cellular = AppSettings.audioQualityCellular.value
    private val override = AppSettings.streamCeilingOverride.value

    @Before
    fun setUp() {
        SourceResolver.SourceCooldown.resetForTest()
        AppSettings.lowPower.value = false
        AppSettings.meteredConnection.value = true
        AppSettings.audioQualityWifi.value = AudioQuality.LOSSLESS
        AppSettings.audioQualityCellular.value = AudioQuality.LOSSLESS
        AppSettings.streamCeilingOverride.value = null
        KoDirectLead.reset()
    }

    @After
    fun restore() {
        AppSettings.lowPower.value = false
        AppSettings.meteredConnection.value = null
        AppSettings.audioQualityWifi.value = wifi
        AppSettings.audioQualityCellular.value = cellular
        AppSettings.streamCeilingOverride.value = override
        KoDirectLead.reset()
        install(emptyList())
    }

    @Test
    fun meteredAtLosslessRunsTheNextTracksHighQualityWork() {
        install(listOf(FakeSource("Addon"), FakeSource("YouTube", SourceKind.YOUTUBE)))

        assertTrue("the lead", KoDirectLead.eligible("hr-next", "hr-next"))
        assertTrue("hq-ahead", KoDirectHqAhead.asksAhead(true, lowPower = false, metered = true, trackEligible = true, hasProof = true, awakeSources = 1))
        assertTrue("download-check ahead", KoDownloadCheckAhead.allowed(metered = true, lowPower = false))
        assertTrue("a lossless pin's read-ahead bytes", bytes(pinnedLossless = true))
    }

    @Test
    fun meteredBelowLosslessKeepsItOff() {
        install(listOf(FakeSource("Addon"), FakeSource("YouTube", SourceKind.YOUTUBE)))
        for (quality in listOf(AudioQuality.HIGH, AudioQuality.MEDIUM, AudioQuality.LOW)) {
            AppSettings.audioQualityCellular.value = quality
            assertFalse("lead at $quality", KoDirectLead.eligible("hr-next", "hr-next"))
            assertFalse("hq-ahead at $quality", KoDirectHqAhead.asksAhead(true, lowPower = false, metered = true, trackEligible = true, hasProof = true, awakeSources = 1))
            assertFalse("download-check at $quality", KoDownloadCheckAhead.allowed(metered = true, lowPower = false))
            assertFalse("pin bytes at $quality", bytes(pinnedLossless = true))
        }
        // The watch's cap on the stream caps this too.
        AppSettings.audioQualityCellular.value = AudioQuality.LOSSLESS
        AppSettings.streamCeilingOverride.value = AudioQuality.MEDIUM
        assertFalse(KoDownloadCheckAhead.allowed(metered = true, lowPower = false))
    }

    @Test
    fun savingPowerAndLossyReadAheadKeepTheOldRule() {
        assertFalse("saving power, metered", KoDownloadCheckAhead.allowed(metered = true, lowPower = true))
        assertFalse("saving power, Wi-Fi", KoDownloadCheckAhead.allowed(metered = false, lowPower = true))
        assertFalse("saving power", bytes(pinnedLossless = true, lowPower = true))
        assertFalse("YouTube's own lossy read-ahead stays off on a metered network", bytes(pinnedLossless = false))
        assertTrue("Wi-Fi as before", bytes(pinnedLossless = false, metered = false))
    }

    private fun bytes(pinnedLossless: Boolean, metered: Boolean = true, lowPower: Boolean = false) = readAheadBytesAllowed(
        metered = metered,
        lowPower = lowPower,
        substitutable = true,
        substitutePinned = true,
        pinnedToYouTube = false,
        playerHoldsHead = false,
        pinnedLossless = pinnedLossless,
    )

    private fun install(sources: List<FakeSource>) {
        SourceRegistry.configs.value = sources.map { source ->
            SourceConfig(
                id = source.configId,
                kind = source.kind,
                label = source.configId,
                baseUrl = if (source.kind == SourceKind.YOUTUBE) "" else "https://${source.configId}",
                enabled = true,
            )
        }
        instancesField.set(SourceRegistry, sources.associateBy { it.configId })
    }

    private val instancesField = SourceRegistry::class.java
        .getDeclaredField("instances")
        .apply { isAccessible = true }

    private class FakeSource(
        override val configId: String,
        override val kind: SourceKind = SourceKind.ADDON,
    ) : MusicSource {
        override val displayName: String get() = configId
        override suspend fun health() = SourceHealth.Ok()
        override suspend fun search(query: String, limit: Int, waitForAll: Boolean, request: StreamRequest?): List<Song> = emptyList()
        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream =
            SourceStream(url = "https://$configId/$trackId", format = StreamFormat("flac"), sourceConfigId = configId, trackId = trackId)
    }
}
