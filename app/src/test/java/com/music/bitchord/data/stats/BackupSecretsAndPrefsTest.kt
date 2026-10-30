package com.music.bitchord.data.stats

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.testutil.FakePreferences
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup is a file the listener may put in Drive, so the preference section
 * has to round-trip every Android type and still leave credentials behind.
 */
class BackupSecretsAndPrefsTest {

    private val prefsField = AppSettings::class.java.getDeclaredField("prefs").apply {
        isAccessible = true
    }
    private val previousPrefs = prefsField.get(AppSettings)

    @After
    fun restorePreferences() {
        prefsField.set(AppSettings, previousPrefs)
    }

    private fun install(prefs: FakePreferences) {
        prefsField.set(AppSettings, prefs.prefs)
    }

    private val secrets = listOf(
        "lastfm_session_key",
        "lastfm_api_key",
        "lastfm_secret",
        "listenbrainz_token",
        "spotify_spdc_token",
        "paxsenix_api_key",
        "gemini_api_key",
        "deepgram_api_key",
    )

    private val deviceLocal = listOf(
        "downloaded_tracks",
        "downloaded_tracks_metadata",
        "downloaded_collections",
        "downloaded_mv_sound_suspects",
        "liked_download_pending",
        "lock_screen_lyrics_page",
        "ko_update_checked_at",
        "ko_upstream_notified_version",
        "local_music_folder_uri",
        "last_version_code",
        "onboarding_complete",
    )

    @Test
    fun `export leaves credentials and device local records behind`() {
        val fake = FakePreferences(
            buildMap {
                put("theme_mode", "DARK")
                put("crossfade_seconds", 4)
                put("wifi_only_downloads", true)
                secrets.forEach { put(it, "secret-value") }
                deviceLocal.forEach { put(it, "device-value") }
            },
        )
        install(fake)

        val exported = AppSettings.exportPrefs()

        assertEquals(setOf("theme_mode", "crossfade_seconds", "wifi_only_downloads"), exported.keys)
        secrets.forEach { assertFalse("$it must not leave the device", it in exported) }
        deviceLocal.forEach { assertFalse("$it describes this device", it in exported) }
    }

    @Test
    fun `a backup built from the exported preferences round-trips without a secret`() {
        val fake = FakePreferences(
            buildMap {
                put("theme_mode", "DARK")
                put("crossfade_seconds", 4)
                put("wifi_only_downloads", true)
                secrets.forEach { put(it, "secret-value") }
                deviceLocal.forEach { put(it, "device-value") }
            },
        )
        install(fake)

        val settings = AppSettings.exportPrefs()
            .mapNotNull { (key, value) ->
                Backup.PrefValue.of(value)?.let { key to it }
            }
            .toMap()
        val file = Backup.BackupFile(
            version = 2,
            versionName = "test-build",
            exportedAt = "2026-10-26T00:00:00Z",
            settings = settings,
        )

        val text = Backup.encode(file)
        val decoded = Backup.decode(text)

        assertEquals(file, decoded)
        secrets.forEach { assertFalse("$it leaked into the backup text", it in text) }
        deviceLocal.forEach { assertFalse("$it leaked into the backup text", it in text) }
        assertEquals("DARK", decoded.settings["theme_mode"]?.decoded())
        assertEquals(4, decoded.settings["crossfade_seconds"]?.decoded())
        assertEquals(true, decoded.settings["wifi_only_downloads"]?.decoded())
    }

    @Test
    fun `pref values keep their exact Android type through json`() {
        val values = listOf<Any>(
            true,
            false,
            7,
            -7,
            9_000_000_000L,
            1.5f,
            "text",
            setOf("a", "b"),
            emptySet<String>(),
        )

        values.forEach { value ->
            val pref = Backup.PrefValue.of(value)
            val decoded = pref?.decoded()
            assertEquals("round trip failed for ${value::class}", value, decoded)
        }
    }

    @Test
    fun `a pref value carries numbers as strings so json cannot choose a type`() {
        val int = Backup.PrefValue.of(42)
        val long = Backup.PrefValue.of(42L)
        val float = Backup.PrefValue.of(0.5f)

        assertEquals("int", int?.type)
        assertEquals("42", int?.value)
        assertEquals("long", long?.type)
        assertEquals("42", long?.value)
        assertEquals("float", float?.type)
        assertEquals("0.5", float?.value)
    }

    @Test
    fun `unsupported values and malformed payloads are nothing to restore`() {
        assertNull(Backup.PrefValue.of(null))
        assertNull(Backup.PrefValue.of(1.5))
        assertNull(Backup.PrefValue.of(listOf("a")))
        assertNull(Backup.PrefValue("int", "not-a-number").decoded())
        assertNull(Backup.PrefValue("long", "not-a-number").decoded())
        assertNull(Backup.PrefValue("float", "not-a-number").decoded())
        assertNull(Backup.PrefValue("bool", "not-a-bool").decoded())
        assertNull(Backup.PrefValue("unknown", "x").decoded())
        assertEquals(setOf("a", "b"), Backup.PrefValue("stringSet", values = listOf("a", "b")).decoded())
    }

    @Test
    fun `unknown json keys are ignored and the oldest schema still validates`() {
        val text = """
            {
              "app": "bitchord",
              "version": 1,
              "settings": {
                "theme_mode": {"type": "string", "value": "LIGHT", "future": 1}
              },
              "futureSection": {"anything": true}
            }
        """.trimIndent()

        val file = Backup.decode(text)
        Backup.validate(file)

        assertEquals(1, file.version)
        assertEquals("LIGHT", file.settings["theme_mode"]?.decoded())
        assertTrue(file.localPlaylists.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a document without the app tag is not a backup`() {
        Backup.validate(Backup.decode("{}"))
    }

    @Test
    fun `a malformed json document is refused with the backup error`() {
        val result = runCatching { Backup.decode("not json") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("백업"))
    }

    @Test
    fun `a playlist row with a blank id is refused only for schema two and later`() {
        val broken = Backup.BackupFile(
            app = "bitchord",
            version = 2,
            localPlaylists = listOf(
                Backup.BackupPlaylist(id = "", title = "Broken"),
            ),
        )

        assertTrue(runCatching { Backup.validate(broken) }.isFailure)

        val old = broken.copy(version = 1)
        Backup.validate(old)
    }

    @Test
    fun `a backup from a newer build is refused before any write`() {
        val result = runCatching {
            Backup.validate(Backup.BackupFile(app = "bitchord", version = 3))
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("최신"))
    }

    @Test
    fun `an oversized backup is refused instead of being read whole`() {
        val stream = ByteArrayInputStream(ByteArray(Backup.MAX_IMPORT_BYTES + 1))

        val result = runCatching { with(Backup) { stream.readBackupText() } }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("큽니다"))
    }

    @Test
    fun `json encodes a typed preference object not a bare number`() {
        val file = Backup.BackupFile(
            version = 2,
            settings = mapOf(
                "crossfade_seconds" to Backup.PrefValue.of(4)!!,
                "equalizer_balance" to Backup.PrefValue.of(0.5f)!!,
            ),
        )

        val text = Backup.encode(file)
        val decoded = Json.decodeFromString<Backup.BackupFile>(text)

        assertEquals(Backup.PrefValue.of(4), decoded.settings["crossfade_seconds"])
        assertEquals(Backup.PrefValue.of(0.5f), decoded.settings["equalizer_balance"])
    }
}
