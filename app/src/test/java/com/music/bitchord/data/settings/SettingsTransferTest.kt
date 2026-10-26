package com.music.bitchord.data.settings

import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.stats.Backup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTransferTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `settings sources and proxy round-trip`() {
        val sources = listOf(
            SourceConfig(
                id = "addon-1",
                kind = SourceKind.ADDON,
                label = "Living room",
                baseUrl = "https://addon.example",
                enabled = false,
            ),
            SourceConfig(kind = SourceKind.JIOSAAVN, enabled = true),
        )
        val proxy = SettingsTransfer.ProxyTransfer(
            mode = ProxyMode.HTTP.name,
            host = "proxy.example",
            port = 8080,
            backupHost = "backup.example",
            backupPort = 8081,
            username = "listener",
            password = "secret",
            scope = ProxyScope.ALL_YOUTUBE.name,
        )
        val file = SettingsTransfer.buildFile(
            rawSettings = mapOf(
                "audio_quality_wifi" to "LOSSLESS",
                "crossfade_seconds" to 4,
                "lyrics_sources" to "LRCLIB,GENIUS",
            ),
            sources = sources,
            proxy = proxy,
            versionName = "test-build",
        )

        val decoded = SettingsTransfer.decode(SettingsTransfer.encode(file)).getOrThrow()

        assertEquals(file, decoded)
        val settings = SettingsTransfer.readSettings(decoded)
        assertEquals("LOSSLESS", settings.values["audio_quality_wifi"])
        assertEquals(4, settings.values["crossfade_seconds"])
        assertEquals("LRCLIB,GENIUS", settings.values["lyrics_sources"])
        assertEquals(0, settings.skipped)

        val parsedSources = SettingsTransfer.readSources(decoded)
        assertEquals(2, parsedSources.values.size)
        assertEquals("Living room", parsedSources.values[0].label)
        assertEquals(false, parsedSources.values[0].enabled)
        assertEquals(SourceKind.JIOSAAVN, parsedSources.values[1].kind)

        val parsedProxy = SettingsTransfer.readProxy(decoded).value
        assertEquals("proxy.example", parsedProxy?.host)
        assertEquals("secret", parsedProxy?.password)
        assertEquals(ProxyScope.ALL_YOUTUBE, parsedProxy?.scope)
    }

    @Test
    fun `credentials history and device-local values never enter the file`() {
        val file = SettingsTransfer.buildFile(
            rawSettings = mapOf(
                "audio_quality_wifi" to "HIGH",
                "gemini_api_key" to "user-key",
                "paxsenix_api_key" to "user-key",
                "lastfm_session_key" to "session",
                "listenbrainz_token" to "token",
                "spotify_spdc_token" to "token",
                "onboarding_complete" to true,
                "local_music_folder_uri" to "content://tree",
                "downloaded_tracks" to "[]",
                "search_history" to "[]",
            ),
            sources = emptyList(),
            proxy = null,
        )

        assertEquals(setOf("audio_quality_wifi"), file.settings.keys)
        assertFalse("gemini_api_key" in AppSettings.TRANSFERABLE_KEYS)
        assertFalse("lastfm_session_key" in AppSettings.TRANSFERABLE_KEYS)
        assertFalse("onboarding_complete" in AppSettings.TRANSFERABLE_KEYS)
        assertFalse("local_music_folder_uri" in AppSettings.TRANSFERABLE_KEYS)
    }

    @Test
    fun `a file from a newer schema is refused`() {
        val file = SettingsTransfer.buildFile(emptyMap(), emptyList(), null)
            .copy(version = SettingsTransfer.SCHEMA_VERSION + 1)

        val result = SettingsTransfer.decode(SettingsTransfer.encode(file))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("최신"))
    }

    @Test
    fun `a file from an unknown older schema is refused`() {
        val file = SettingsTransfer.buildFile(emptyMap(), emptyList(), null)
            .copy(version = 0)

        val result = SettingsTransfer.decode(SettingsTransfer.encode(file))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("버전"))
    }

    @Test
    fun `unknown keys and wrong values are skipped while the rest apply`() {
        fun pref(type: String, value: String) = json.encodeToJsonElement(
            Backup.PrefValue.serializer(),
            Backup.PrefValue(type = type, value = value),
        )

        val file = SettingsTransfer.File(
            settings = mapOf(
                "crossfade_seconds" to pref("int", "5"),
                "audio_quality_wifi" to pref("string", "TURBO"),
                "lyrics_offset_ms" to pref("bool", "true"),
                "not_a_setting" to JsonPrimitive("anything"),
            ),
            sources = listOf(JsonPrimitive("not a source")),
            proxy = json.encodeToJsonElement(
                SettingsTransfer.ProxyTransfer.serializer(),
                SettingsTransfer.ProxyTransfer(mode = "NOT_A_MODE"),
            ),
        )

        val preview = SettingsTransfer.previewOf(
            file = file,
            currentSettings = emptyMap(),
            currentSources = emptyList(),
            currentProxy = ProxyConfig(),
        )

        assertEquals(setOf("crossfade_seconds"), preview.settings.keys)
        assertEquals(5, preview.skipped) // three bad settings, one source, one proxy
        assertFalse(preview.sourcesChanged)
        assertEquals(null, preview.proxy)
    }
}
