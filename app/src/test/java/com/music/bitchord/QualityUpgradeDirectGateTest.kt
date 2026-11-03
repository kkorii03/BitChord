package com.music.bitchord

import android.net.FakeUri
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Direct Source Stream holds the *background* second look off a track it knows
 * is already an addon's copy. It must not hold it off a track whose copy is
 * unknown: a replay from the disk cache has no in-memory [StreamChoice] at all,
 * and 1.8 still offered that track a better copy. Suppressing it there left
 * every restored YouTube track stuck at its old bitrate for the session.
 */
class QualityUpgradeDirectGateTest {

    private val id = "direct-gate-track"

    private val instancesField = SourceRegistry::class.java
        .getDeclaredField("instances")
        .apply { isAccessible = true }

    private var configs = emptyList<SourceConfig>()
    private var direct = false
    private var wifi = AudioQuality.LOSSLESS
    private var metered: Boolean? = false
    private var override: AudioQuality? = null

    @Before
    fun setUp() {
        configs = SourceRegistry.configs.value
        direct = AppSettings.directSourceStream.value
        wifi = AppSettings.audioQualityWifi.value
        metered = AppSettings.meteredConnection.value
        override = AppSettings.streamCeilingOverride.value

        // A lossless ceiling keeps the addon above YouTube; HIGH would filter
        // the addon out before the gate ever sees it.
        AppSettings.audioQualityWifi.value = AudioQuality.LOSSLESS
        AppSettings.meteredConnection.value = false
        AppSettings.streamCeilingOverride.value = null
        AppSettings.directSourceStream.value = true
        install()
        QualityUpgrade.forget(id)
        StreamChoice.forget(id)
    }

    @After
    fun tearDown() {
        QualityUpgrade.forget(id)
        StreamChoice.forget(id)
        SourceRegistry.configs.value = configs
        instancesField.set(SourceRegistry, emptyMap<String, MusicSource>())
        AppSettings.directSourceStream.value = direct
        AppSettings.audioQualityWifi.value = wifi
        AppSettings.meteredConnection.value = metered
        AppSettings.streamCeilingOverride.value = override
    }

    private fun install() {
        val addon = FakeSource("addon", SourceKind.ADDON)
        val youtube = FakeSource("youtube", SourceKind.YOUTUBE)
        SourceRegistry.configs.value = listOf(addon, youtube).map { source ->
            SourceConfig(
                id = source.configId,
                kind = source.kind,
                label = source.configId,
                baseUrl = if (source.kind == SourceKind.YOUTUBE) "" else "https://${source.configId}",
            )
        }
        instancesField.set(SourceRegistry, listOf(addon, youtube).associateBy { it.configId })
    }

    private fun watchUri() = FakeUri("bitchord://watch?v=$id")

    private fun substitute() = SourceStream(
        url = "https://addon.example/$id",
        sourceConfigId = "addon",
        trackId = id,
    )

    private fun youTube() = SourceStream(url = "https://youtube.example/$id")

    @Test
    fun `a cached track with no choice is still offered an upgrade under direct source`() {
        assertTrue(
            "an unknown copy is not a known substitute; 1.8 still looked at it",
            QualityUpgrade.couldStillUpgrade(id, watchUri()),
        )
    }

    @Test
    fun `a known addon substitute is not hunted in the background under direct source`() {
        StreamChoice.remember(id, substitute(), substituted = true)

        assertFalse(QualityUpgrade.couldStillUpgrade(id, watchUri()))
    }

    @Test
    fun `a YouTube choice is still offered an upgrade under direct source`() {
        StreamChoice.remember(id, youTube(), substituted = false)

        assertTrue(QualityUpgrade.couldStillUpgrade(id, watchUri()))
    }

    @Test
    fun `a hand request overrules the direct-source hold`() {
        StreamChoice.remember(id, substitute(), substituted = true)
        QualityUpgrade.askByHand(id)

        assertTrue(QualityUpgrade.couldStillUpgrade(id, watchUri()))
    }

    @Test
    fun `direct source off keeps the old behaviour for a substitute`() {
        AppSettings.directSourceStream.value = false
        StreamChoice.remember(id, substitute(), substituted = true)

        assertTrue(QualityUpgrade.couldStillUpgrade(id, watchUri()))
    }

    private class FakeSource(
        override val configId: String,
        override val kind: SourceKind,
    ) : MusicSource {
        override val displayName: String get() = configId

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ) = emptyList<com.music.bitchord.data.model.Song>()

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
            SourceStream(url = "https://$configId.example/$trackId", format = StreamFormat())
    }
}
