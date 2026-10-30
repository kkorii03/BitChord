package com.music.bitchord.data.settings

import android.content.Context
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Restored preferences are untrusted: every known key must arrive as its own
 * carrier type, inside the bounds its setter accepts. A wrong type must be
 * dropped before it reaches the file, because Android's typed getter throws on
 * the next read — after the import has already cleared the old values.
 */
class AppSettingsImportValidationTest {

    private lateinit var directory: File
    private lateinit var preferences: FakePreferences
    private lateinit var authPreferences: FakePreferences
    private lateinit var context: Context

    private val prefsField = field("prefs")
    private val appContextField = field("appContext")
    private val authStoreField = field("authStore")

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-app-settings").toFile()
        preferences = FakePreferences()
        authPreferences = FakePreferences()
        context = FakeContext.create(preferences, directory)
        appContextField.set(AppSettings, context)
        authStoreField.set(AppSettings, fakeAuthStore(authPreferences))
        prefsField.set(AppSettings, preferences.prefs)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `an update after onboarding leaves the HQ automation switches off`() {
        preferences.values["onboarding_complete"] = true
        preferences.values["audio_quality_wifi"] = "LOSSLESS"

        AppSettings.reload()

        assertFalse(AppSettings.autoReplaceHqDownloads.value)
        assertFalse(AppSettings.autoPrefetchHq.value)
        assertEquals(false, preferences.values["auto_replace_hq_downloads"])
        assertEquals(false, preferences.values["auto_prefetch_hq"])
    }

    @Test
    fun `a fresh install keeps the HQ automation switches on`() {
        AppSettings.reload()

        assertTrue(AppSettings.autoReplaceHqDownloads.value)
        assertTrue(AppSettings.autoPrefetchHq.value)
        assertEquals(true, preferences.values["auto_replace_hq_downloads"])
        assertEquals(true, preferences.values["auto_prefetch_hq"])
    }

    @Test
    fun `wrongly typed known values are dropped instead of breaking reload`() {
        AppSettings.importPrefs(
            mapOf(
                "playback_speed" to "fast",
                "repeat_mode" to "all",
                "equalizer_bands" to 3,
                "crossfade_seconds" to "four",
                "theme_mode" to 1,
                "ko_cover_channels" to "not-a-set",
                "lyrics_offset_ms" to 1_000L,
                "hq_prefetch_limit" to "many",
            ),
        )

        listOf(
            "playback_speed",
            "repeat_mode",
            "equalizer_bands",
            "crossfade_seconds",
            "theme_mode",
            "ko_cover_channels",
            "lyrics_offset_ms",
            "hq_prefetch_limit",
        ).forEach { key ->
            assertFalse("$key should have been dropped", preferences.values.containsKey(key))
        }
        assertEquals(1.0f, AppSettings.playbackSpeed.value, 0f)
    }

    @Test
    fun `out of range numbers are clamped to what the setters accept`() {
        AppSettings.importPrefs(
            mapOf(
                "playback_speed" to 9f,
                "repeat_mode" to 99,
                "equalizer_balance" to -3f,
                "scrobble_delay_percent" to 5f,
                "equalizer_bands" to "-999,0,999",
            ),
        )

        assertEquals(2.0f, AppSettings.playbackSpeed.value, 0f)
        assertEquals(2, AppSettings.repeatMode.value)
        assertEquals(-1f, AppSettings.equalizerBalance.value, 0f)
        assertEquals(1f, AppSettings.scrobbleDelayPercent.value, 0f)
        assertEquals(listOf(-12f, 0f, 12f), AppSettings.equalizerBands.value.take(3))
    }

    @Test
    fun `non-finite floats and unknown enum rungs are dropped`() {
        AppSettings.importPrefs(
            mapOf(
                "playback_speed" to Float.NaN,
                "equalizer_balance" to Float.POSITIVE_INFINITY,
                "theme_mode" to "TURBO",
                "audio_quality_wifi" to "TURBO",
                "equalizer_bands" to "1,NaN",
            ),
        )

        assertFalse(preferences.values.containsKey("playback_speed"))
        assertFalse(preferences.values.containsKey("equalizer_balance"))
        assertFalse(preferences.values.containsKey("theme_mode"))
        assertFalse(preferences.values.containsKey("audio_quality_wifi"))
        assertFalse(preferences.values.containsKey("equalizer_bands"))
        assertEquals(ThemeMode.DARK, AppSettings.themeMode.value)
    }

    @Test
    fun `legacy secrets move from the plain settings file into the encrypted store`() {
        preferences.values["gemini_api_key"] = "legacy-gemini"
        preferences.values["listenbrainz_token"] = "legacy-listenbrainz"
        preferences.values["theme_mode"] = "DARK"

        AppSettings.migrateSecretsToAuthStore()

        assertFalse(preferences.values.containsKey("gemini_api_key"))
        assertFalse(preferences.values.containsKey("listenbrainz_token"))
        assertEquals("legacy-gemini", authPreferences.values["gemini_api_key"])
        assertEquals("legacy-listenbrainz", authPreferences.values["listenbrainz_token"])
        assertEquals("DARK", preferences.values["theme_mode"])
    }

    @Test
    fun `settings setters keep credentials out of the plain settings file`() {
        val secrets = setOf(
            "gemini_api_key",
            "deepgram_api_key",
            "paxsenix_api_key",
            "listenbrainz_token",
            "spotify_spdc_token",
            "lastfm_session_key",
        )

        AppSettings.setGeminiApiKey("gemini-value")
        AppSettings.setDeepgramApiKey("deepgram-value")
        AppSettings.setPaxSenixApiKey("pax-value")
        AppSettings.setListenBrainzToken("listenbrainz-value")
        AppSettings.setSpotifySpdcToken("spotify-value")
        AppSettings.setLastfmSessionKey("lastfm-value")

        assertFalse(preferences.values.keys.any { it in secrets })
        assertEquals("gemini-value", authPreferences.values["gemini_api_key"])
        assertEquals("deepgram-value", authPreferences.values["deepgram_api_key"])
        assertEquals("pax-value", authPreferences.values["paxsenix_api_key"])
        assertEquals("listenbrainz-value", authPreferences.values["listenbrainz_token"])
        assertEquals("spotify-value", authPreferences.values["spotify_spdc_token"])
        assertEquals("lastfm-value", authPreferences.values["lastfm_session_key"])
    }

    @Test
    fun `the HQ automation default is off only for an install that already has settings`() {
        assertEquals(false, hqAutomationDefault(existingInstall = true))
        assertEquals(true, hqAutomationDefault(existingInstall = false))
    }

    private fun field(name: String) =
        AppSettings::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** AuthStore without its encrypted-file constructor; its secrets stay empty. */
    private fun fakeAuthStore(preferences: FakePreferences): AuthStore {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val store = allocate.invoke(unsafe, AuthStore::class.java) as AuthStore
        AuthStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(store, preferences.prefs)
        return store
    }
}
