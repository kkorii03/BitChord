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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The validation edges of a settings transfer: what one malformed row does to
 * the rest of the file, and which values this build is allowed to write back.
 */
class SettingsTransferEdgeCasesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun pref(type: String, value: String? = null, values: List<String> = emptyList()) =
        json.encodeToJsonElement(
            Backup.PrefValue.serializer(),
            Backup.PrefValue(type = type, value = value, values = values),
        )

    private fun source(value: SourceConfig) =
        json.encodeToJsonElement(SourceConfig.serializer(), value)

    @Test
    fun `a file that names another app is refused`() {
        val result = SettingsTransfer.decode(
            """{"app":"another-app","version":1,"settings":{},"sources":[]}""",
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("BitChord"))
    }

    @Test
    fun `malformed json is refused rather than half read`() {
        assertTrue(SettingsTransfer.decode("{").isFailure)
        assertTrue(SettingsTransfer.decode("").isFailure)
    }

    @Test
    fun `a document without the app tag is not a settings file`() {
        assertTrue(SettingsTransfer.decode("{}").isFailure)
    }

    @Test
    fun `boolean int long and float specs reject the wrong carrier type`() {
        val bool = TransferSpec(TransferType.BOOLEAN)
        assertEquals(true, bool.normalize(true))
        assertNull(bool.normalize("true"))
        assertNull(bool.normalize(1))

        val int = TransferSpec(TransferType.INT)
        assertEquals(7, int.normalize(7))
        assertNull(int.normalize(7L))
        assertNull(int.normalize(7.0))
        assertNull(int.normalize("7"))

        val long = TransferSpec(TransferType.LONG)
        assertEquals(7L, long.normalize(7L))
        assertNull(long.normalize(7))

        val float = TransferSpec(TransferType.FLOAT)
        assertEquals(1.5f, float.normalize(1.5f))
        assertNull(float.normalize(1.5))
        assertNull(float.normalize("1.5"))
    }

    @Test
    fun `a string spec accepts only the named rungs and is case sensitive`() {
        val spec = TransferSpec(TransferType.STRING, setOf("A", "B"))

        assertEquals("A", spec.normalize("A"))
        assertNull(spec.normalize("a"))
        assertNull(spec.normalize("C"))
        assertNull(spec.normalize(1))
        assertEquals("anything", TransferSpec(TransferType.STRING).normalize("anything"))
    }

    @Test
    fun `a string list trims drops blanks and refuses a wholly unknown list`() {
        val spec = TransferSpec(TransferType.STRING_LIST, setOf("A", "B"))

        assertEquals("A,B", spec.normalize(" A , B , "))
        assertEquals("A", spec.normalize("A,C"))
        assertNull(spec.normalize("C,D"))
        assertEquals("", spec.normalize(""))
        assertNull(spec.normalize(3))
        assertEquals("C,D", TransferSpec(TransferType.STRING_LIST).normalize("C, D"))
    }

    @Test
    fun `a string set keeps only allowed names and treats an empty set as empty`() {
        val spec = TransferSpec(TransferType.STRING_SET, setOf("A", "B"))

        assertEquals(setOf("A", "B"), spec.normalize(setOf("B", "A", "C")))
        assertNull(spec.normalize(setOf("C")))
        assertEquals(emptySet<String>(), spec.normalize(emptySet<String>()))
        assertNull(spec.normalize(listOf("A")))
    }

    @Test
    fun `a float list normalizes spacing and rejects any non number`() {
        val spec = TransferSpec(TransferType.FLOAT_LIST)

        assertEquals("1,-2.5,3", spec.normalize(" 1 , -2.5 , 3 "))
        assertNull(spec.normalize("1,x"))
        assertNull(spec.normalize(""))
        assertNull(spec.normalize(1.0f))
    }

    @Test
    fun `bad settings are skipped one by one while good rows still apply`() {
        val file = SettingsTransfer.File(
            settings = mapOf(
                "crossfade_seconds" to pref("int", "not-a-number"),
                "audio_quality_wifi" to pref("string", "TURBO"),
                "lyrics_sources" to pref("string", "LRC_RED,NOT_A_SOURCE"),
                "lyrics_offset_ms" to pref("long", "1000"),
                "lyrics_offset_ms_again" to pref("int", "1000"),
                "not_a_setting" to pref("string", "x"),
            ),
        )

        val parsed = SettingsTransfer.readSettings(file)

        assertEquals("LRC_RED", parsed.values["lyrics_sources"])
        assertEquals(1, parsed.values.size)
        assertEquals(5, parsed.skipped)
    }

    @Test
    fun `sources are trimmed deduplicated and given an id when blank`() {
        val file = SettingsTransfer.File(
            sources = listOf(
                source(
                    SourceConfig(
                        id = "addon-a",
                        kind = SourceKind.ADDON,
                        label = " Living room ",
                        baseUrl = " https://addon.example ",
                    ),
                ),
                source(
                    SourceConfig(
                        id = "addon-a",
                        kind = SourceKind.ADDON,
                        label = "Duplicate id",
                        baseUrl = "https://other.example",
                    ),
                ),
                source(
                    SourceConfig(
                        id = "",
                        kind = SourceKind.ADDON,
                        label = " No id ",
                        baseUrl = "https://third.example",
                    ),
                ),
                source(SourceConfig(kind = SourceKind.MODULE, baseUrl = "https://module.example")),
                source(SourceConfig(kind = SourceKind.ADDON, label = "No server")),
                source(SourceConfig(kind = SourceKind.JIOSAAVN)),
                source(SourceConfig(kind = SourceKind.JIOSAAVN, enabled = false)),
                JsonPrimitive("not a source"),
            ),
        )

        val parsed = SettingsTransfer.readSources(file)

        assertEquals(3, parsed.values.size)
        assertEquals("Living room", parsed.values[0].label)
        assertEquals("https://addon.example", parsed.values[0].baseUrl)
        assertEquals("No id", parsed.values[1].label)
        assertTrue(parsed.values[1].id.isNotBlank())
        assertEquals(SourceKind.JIOSAAVN, parsed.values[2].kind)
        assertEquals(3, parsed.skipped)
    }

    @Test
    fun `a proxy is normalized and an unknown mode scope or port is skipped`() {
        val valid = SettingsTransfer.File(
            proxy = json.encodeToJsonElement(
                SettingsTransfer.ProxyTransfer.serializer(),
                SettingsTransfer.ProxyTransfer(
                    mode = ProxyMode.HTTP.name,
                    host = " proxy.example ",
                    port = 8080,
                    backupHost = "",
                    backupPort = 1234,
                    username = " listener ",
                    password = " secret ",
                    scope = ProxyScope.ALL_YOUTUBE.name,
                ),
            ),
        )
        val parsed = SettingsTransfer.readProxy(valid).value

        assertEquals("proxy.example", parsed?.host)
        assertEquals(8080, parsed?.port)
        assertEquals("listener", parsed?.username)
        assertEquals(" secret ", parsed?.password)
        assertEquals(0, parsed?.backupPort)
        assertEquals(ProxyScope.ALL_YOUTUBE, parsed?.scope)

        fun invalid(proxy: SettingsTransfer.ProxyTransfer) = SettingsTransfer.readProxy(
            SettingsTransfer.File(
                proxy = json.encodeToJsonElement(
                    SettingsTransfer.ProxyTransfer.serializer(),
                    proxy,
                ),
            ),
        )

        assertEquals(1, invalid(SettingsTransfer.ProxyTransfer(mode = "NOT_A_MODE")).skipped)
        assertEquals(
            1,
            invalid(SettingsTransfer.ProxyTransfer(mode = ProxyMode.HTTP.name, scope = "NOT_A_SCOPE")).skipped,
        )
        assertEquals(
            1,
            invalid(SettingsTransfer.ProxyTransfer(mode = ProxyMode.HTTP.name, port = 65_536)).skipped,
        )
        assertEquals(
            1,
            invalid(SettingsTransfer.ProxyTransfer(mode = ProxyMode.HTTP.name, backupPort = -1)).skipped,
        )
        assertEquals(0, SettingsTransfer.readProxy(SettingsTransfer.File()).skipped)
    }

    @Test
    fun `an enabled proxy without a host or a real port is not a configuration`() {
        val transfer = SettingsTransfer.ProxyTransfer(
            mode = ProxyMode.HTTP.name,
            host = "",
            port = 0,
        )

        assertNull(transfer.toConfig())
    }

    @Test
    fun `preview compares numbers by value and reports only real changes`() {
        val file = SettingsTransfer.File(
            settings = mapOf("crossfade_seconds" to pref("int", "4")),
        )

        val same = SettingsTransfer.previewOf(
            file = file,
            currentSettings = mapOf("crossfade_seconds" to 4.0),
            currentSources = emptyList(),
            currentProxy = ProxyConfig(),
        )
        assertTrue(same.changedSettings.isEmpty())

        val changed = SettingsTransfer.previewOf(
            file = file,
            currentSettings = mapOf("crossfade_seconds" to 3),
            currentSources = emptyList(),
            currentProxy = ProxyConfig(),
        )
        assertEquals(mapOf("crossfade_seconds" to 4), changed.changedSettings)
    }

    @Test
    fun `preview treats the same source list as unchanged and an empty import as no change`() {
        val jiosaavn = SourceConfig(id = "jio", kind = SourceKind.JIOSAAVN)
        val file = SettingsTransfer.File(sources = listOf(source(jiosaavn)))

        val same = SettingsTransfer.previewOf(
            file = file,
            currentSettings = emptyMap(),
            currentSources = listOf(jiosaavn),
            currentProxy = ProxyConfig(),
        )
        assertFalse(same.sourcesChanged)

        val reordered = SettingsTransfer.previewOf(
            file = SettingsTransfer.File(
                sources = listOf(
                    source(SourceConfig(id = "a", kind = SourceKind.ADDON, baseUrl = "https://a")),
                    source(SourceConfig(id = "b", kind = SourceKind.ADDON, baseUrl = "https://b")),
                ),
            ),
            currentSettings = emptyMap(),
            currentSources = listOf(
                SourceConfig(id = "b", kind = SourceKind.ADDON, baseUrl = "https://b"),
                SourceConfig(id = "a", kind = SourceKind.ADDON, baseUrl = "https://a"),
            ),
            currentProxy = ProxyConfig(),
        )
        assertTrue(reordered.sourcesChanged)

        val empty = SettingsTransfer.previewOf(
            file = SettingsTransfer.File(),
            currentSettings = emptyMap(),
            currentSources = listOf(jiosaavn),
            currentProxy = ProxyConfig(),
        )
        assertFalse(empty.sourcesChanged)
    }

    @Test
    fun `preview sees a proxy change and counts every skipped row`() {
        val config = ProxyConfig(
            mode = ProxyMode.HTTP,
            host = "proxy.example",
            port = 8080,
            password = "old",
        )
        val same = SettingsTransfer.previewOf(
            file = SettingsTransfer.File(
                proxy = json.encodeToJsonElement(
                    SettingsTransfer.ProxyTransfer.serializer(),
                    SettingsTransfer.ProxyTransfer.from(config),
                ),
            ),
            currentSettings = emptyMap(),
            currentSources = emptyList(),
            currentProxy = config,
        )
        assertFalse(same.proxyChanged)

        val file = SettingsTransfer.File(
            settings = mapOf(
                "audio_quality_wifi" to pref("string", "TURBO"),
                "not_a_setting" to pref("string", "x"),
            ),
            sources = listOf(JsonPrimitive("not a source")),
            proxy = json.encodeToJsonElement(
                SettingsTransfer.ProxyTransfer.serializer(),
                SettingsTransfer.ProxyTransfer(mode = "NOT_A_MODE"),
            ),
        )
        val changed = SettingsTransfer.previewOf(
            file = file,
            currentSettings = emptyMap(),
            currentSources = emptyList(),
            currentProxy = config,
        )

        assertEquals(4, changed.skipped)
        assertNull(changed.proxy)
        assertFalse(changed.proxyChanged)
    }

    @Test
    fun `build file keeps only transferable typed values and records its metadata`() {
        val now = Instant.parse("2026-10-26T12:34:56Z")
        val file = SettingsTransfer.buildFile(
            rawSettings = mapOf(
                "audio_quality_wifi" to "HIGH",
                "crossfade_seconds" to 4,
                "equalizer_balance" to 0.5f,
                "equalizer_bands" to "0.0,1.5,-2.0",
                "lyrics_sources" to "LRC_RED",
                "lyrics_offset_ms" to 1.5,
                "gemini_api_key" to "secret",
                "downloaded_tracks" to "[]",
                "not_a_setting" to "x",
                "missing" to null,
            ),
            sources = listOf(SourceConfig(id = "jio", kind = SourceKind.JIOSAAVN)),
            proxy = SettingsTransfer.ProxyTransfer(mode = ProxyMode.OFF.name),
            now = now,
            versionName = "test-build",
        )

        assertEquals(SettingsTransfer.SCHEMA_VERSION, file.version)
        assertEquals("test-build", file.versionName)
        assertEquals(now.toString(), file.exportedAt)
        assertEquals(
            setOf(
                "audio_quality_wifi",
                "crossfade_seconds",
                "equalizer_balance",
                "equalizer_bands",
                "lyrics_sources",
            ),
            file.settings.keys,
        )
        assertEquals(1, file.sources.size)

        val text = SettingsTransfer.encode(file)
        assertFalse(text.contains("gemini_api_key"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("downloaded_tracks"))

        val reread = SettingsTransfer.readSettings(SettingsTransfer.decode(text).getOrThrow())
        assertEquals("HIGH", reread.values["audio_quality_wifi"])
        assertEquals(4, reread.values["crossfade_seconds"])
        assertEquals(0.5f, reread.values["equalizer_balance"])
        assertEquals("0.0,1.5,-2.0", reread.values["equalizer_bands"])
        assertEquals("LRC_RED", reread.values["lyrics_sources"])
    }

    @Test
    fun `suggested name carries the extension and a date`() {
        val name = SettingsTransfer.suggestedName()

        assertTrue(name.startsWith("bitchord-settings-"))
        assertTrue(name.endsWith(".${SettingsTransfer.FILE_EXTENSION}"))
        assertNotEquals("bitchord-settings-.${SettingsTransfer.FILE_EXTENSION}", name)
    }

    @Test
    fun `playback speed and repeat mode are clamped to values the player accepts`() {
        val speed = AppSettings.TRANSFERABLE_KEYS.getValue("playback_speed").spec
        assertEquals(0.5f, speed.normalize(0f))
        assertEquals(0.5f, speed.normalize(-4f))
        assertEquals(2.0f, speed.normalize(9f))
        assertNull(speed.normalize(Float.NaN))
        assertNull(speed.normalize(Float.POSITIVE_INFINITY))

        val repeat = AppSettings.TRANSFERABLE_KEYS.getValue("repeat_mode").spec
        assertEquals(0, repeat.normalize(-1))
        assertEquals(2, repeat.normalize(99))
        assertEquals(1, repeat.normalize(1))
    }

    @Test
    fun `float lists reject non-finite values and clamp each gain`() {
        val bands = AppSettings.TRANSFERABLE_KEYS.getValue("equalizer_bands").spec

        assertEquals("-12.0,0.0,12.0", bands.normalize("-999,0,999"))
        assertNull(bands.normalize("1,NaN"))
        assertNull(bands.normalize("1,Infinity"))
    }
}
