package com.music.bitchord.data.settings

import android.content.Context
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.GroqWhisper
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.ko.KoDeferredDownloadTranslation
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Review S1 + S2 (11-17). A settings restore replaces the preference file but
 * keeps what describes this phone: the downloads still waiting for their kept
 * translation, the liked-download retry counts (their queue was already kept),
 * and the "key removed" marks, which must survive like the secrets they stand for.
 */
class DeviceLocalDownloadRecordsImportTest {

    private lateinit var directory: File
    private lateinit var preferences: FakePreferences
    private lateinit var authPreferences: FakePreferences
    private lateinit var context: Context

    private val prefsField = field("prefs")
    private val appContextField = field("appContext")
    private val authStoreField = field("authStore")

    private val originalPrefs = runCatching { prefsField.get(AppSettings) }.getOrNull()
    private val originalContext = runCatching { appContextField.get(AppSettings) }.getOrNull()
    private val originalAuth = runCatching { authStoreField.get(AppSettings) }.getOrNull()
    private val savedDeepgram = Deepgram.apiKey
    private val savedGroq = GroqWhisper.apiKey
    private val savedGemini = LyricsTranslation.geminiApiKey

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-device-local").toFile()
        preferences = FakePreferences()
        authPreferences = FakePreferences()
        context = FakeContext.create(preferences, directory)
        appContextField.set(AppSettings, context)
        authStoreField.set(AppSettings, fakeAuthStore(authPreferences))
        prefsField.set(AppSettings, preferences.prefs)
    }

    @After
    fun tearDown() {
        Deepgram.apiKey = savedDeepgram
        GroqWhisper.apiKey = savedGroq
        LyricsTranslation.geminiApiKey = savedGemini
        authPreferences.values.clear()
        if (originalPrefs != null) prefsField.set(AppSettings, originalPrefs)
        if (originalContext != null) appContextField.set(AppSettings, originalContext)
        if (originalAuth != null) authStoreField.set(AppSettings, originalAuth)
        directory.deleteRecursively()
    }

    @Test
    fun `a settings restore keeps this phone's download records and key removals`() {
        KoDeferredDownloadTranslation.defer(context, "vidA")
        KoDeferredDownloadTranslation.defer(context, "vidB")
        val local = mapOf<String, Any>(
            "liked_download_failures" to "vidC=2",
            "deepgram_api_key_removed" to true,
            "gemini_api_key_removed" to true,
        )
        local.forEach { (key, value) -> preferences.values[key] = value }

        // A backup from another phone that carries its own copies.
        AppSettings.importPrefs(
            mapOf(
                "theme_mode" to "LIGHT",
                "ko_deferred_download_translation" to "otherPhone",
                "liked_download_failures" to "otherPhone=1",
                "deepgram_api_key_removed" to false,
            ),
        )

        assertEquals(listOf("vidA", "vidB"), KoDeferredDownloadTranslation.deferred(context))
        local.forEach { (key, value) ->
            assertEquals("$key belongs to this device", value, preferences.values[key])
        }
        assertEquals("LIGHT", preferences.values["theme_mode"])
    }

    @Test
    fun `a backup leaves out this phone's download records and key removals`() {
        KoDeferredDownloadTranslation.defer(context, "vidA")
        preferences.values["liked_download_failures"] = "vidC=2"
        preferences.values["deepgram_api_key_removed"] = true
        preferences.values["gemini_api_key_removed"] = true
        preferences.values["theme_mode"] = "LIGHT"

        val exported = AppSettings.exportPrefs()

        listOf(
            "ko_deferred_download_translation",
            "liked_download_failures",
            "deepgram_api_key_removed",
            "gemini_api_key_removed",
        ).forEach { key -> assertFalse("$key left the device", key in exported) }
        assertEquals("LIGHT", exported["theme_mode"])
    }

    private fun field(name: String) =
        AppSettings::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** AuthStore without its encrypted-file constructor, over [preferences]. */
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
