package com.music.bitchord.data.settings

import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.lyrics.Deepgram
import com.music.bitchord.data.lyrics.GroqWhisper
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.ko.KoAppSettings
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
 * Review S2 (11-17). The encrypted store drops a blank secret, so "Remove" on
 * the Deepgram or Gemini key used to read as "never set" at the next app start
 * and the key bundled with the build came back: Deepgram got audio again.
 * A removal over a bundled key must outlive the restart; a typed key ends it.
 */
class RemovedBundledKeyStaysRemovedTest {

    private lateinit var directory: File
    private lateinit var preferences: FakePreferences
    private lateinit var authPreferences: FakePreferences

    private val prefsField = appSettingsField("prefs")
    private val appContextField = appSettingsField("appContext")
    private val authStoreField = appSettingsField("authStore")
    private val bundledDeepgramField = koField("bundledDeepgramApiKey")
    private val bundledGeminiField = koField("bundledGeminiApiKey")

    private val originalPrefs = runCatching { prefsField.get(AppSettings) }.getOrNull()
    private val originalContext = runCatching { appContextField.get(AppSettings) }.getOrNull()
    private val originalAuth = runCatching { authStoreField.get(AppSettings) }.getOrNull()
    private val originalBundledDeepgram = bundledDeepgramField.get(KoAppSettings)
    private val originalBundledGemini = bundledGeminiField.get(KoAppSettings)
    private val savedDeepgram = Deepgram.apiKey
    private val savedGroq = GroqWhisper.apiKey
    private val savedGemini = LyricsTranslation.geminiApiKey
    private val savedGeminiEnabled = LyricsTranslation.geminiEnabled
    private val savedDeepgramFlow = KoAppSettings.deepgramApiKey.value
    private val savedGeminiFlow = KoAppSettings.geminiApiKey.value
    private val savedGeminiTranslationFlow = KoAppSettings.geminiTranslation.value

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-removed-key").toFile()
        preferences = FakePreferences()
        authPreferences = FakePreferences()
        appContextField.set(AppSettings, FakeContext.create(preferences, directory))
        authStoreField.set(AppSettings, fakeAuthStore(authPreferences))
        prefsField.set(AppSettings, preferences.prefs)
        bundledDeepgramField.set(KoAppSettings, BUNDLED)
        bundledGeminiField.set(KoAppSettings, BUNDLED)
    }

    @After
    fun tearDown() {
        // All app unit tests share one JVM: a key left here sends later classes
        // down the AI path (review FLAKE-1).
        bundledDeepgramField.set(KoAppSettings, originalBundledDeepgram)
        bundledGeminiField.set(KoAppSettings, originalBundledGemini)
        Deepgram.apiKey = savedDeepgram
        GroqWhisper.apiKey = savedGroq
        LyricsTranslation.geminiApiKey = savedGemini
        LyricsTranslation.geminiEnabled = savedGeminiEnabled
        KoAppSettings.deepgramApiKey.value = savedDeepgramFlow
        KoAppSettings.geminiApiKey.value = savedGeminiFlow
        KoAppSettings.geminiTranslation.value = savedGeminiTranslationFlow
        authPreferences.values.clear()
        if (originalPrefs != null) prefsField.set(AppSettings, originalPrefs)
        if (originalContext != null) appContextField.set(AppSettings, originalContext)
        if (originalAuth != null) authStoreField.set(AppSettings, originalAuth)
        directory.deleteRecursively()
    }

    @Test
    fun `a removed Deepgram key stays removed after a restart`() {
        KoAppSettings.readBundledKeys()
        assertEquals(BUNDLED, KoAppSettings.deepgramApiKey.value)

        AppSettings.setDeepgramApiKey("")
        // What AppSettings.init reads on the next start.
        KoAppSettings.readBundledKeys()
        AppSettings.reload()

        assertEquals("", KoAppSettings.deepgramApiKey.value)
        assertEquals("", Deepgram.apiKey)
        assertFalse(AppSettings.deepgramKeyIsBundled)
    }

    @Test
    fun `a removed Gemini key stays removed after a restart`() {
        AppSettings.setGeminiApiKey("")
        AppSettings.setGeminiTranslation(false)
        KoAppSettings.readBundledKeys()
        AppSettings.reload()

        assertEquals("", KoAppSettings.geminiApiKey.value)
        assertEquals("", LyricsTranslation.geminiApiKey)

        // A restore that carried no translation switch must not turn it back on
        // from the bundled key either.
        preferences.values.remove("gemini_translation")
        AppSettings.reload()
        assertEquals("", KoAppSettings.geminiApiKey.value)
        assertFalse(KoAppSettings.geminiTranslation.value)
    }

    @Test
    fun `a key typed after a removal clears the removal`() {
        AppSettings.setDeepgramApiKey("")
        AppSettings.setGeminiApiKey("")
        KoAppSettings.readBundledKeys()
        AppSettings.reload()
        assertEquals("", KoAppSettings.deepgramApiKey.value)
        assertEquals("", KoAppSettings.geminiApiKey.value)

        AppSettings.setDeepgramApiKey("test-typed")
        AppSettings.setGeminiApiKey("test-typed")
        KoAppSettings.readBundledKeys()
        AppSettings.reload()
        assertEquals("test-typed", KoAppSettings.deepgramApiKey.value)
        assertEquals("test-typed", KoAppSettings.geminiApiKey.value)
        assertFalse(preferences.values.containsKey("deepgram_api_key_removed"))
        assertFalse(preferences.values.containsKey("gemini_api_key_removed"))

        // With the removal cleared, a store that later loses the typed key falls
        // back to the bundled one again, as before any removal.
        authPreferences.values.clear()
        KoAppSettings.readBundledKeys()
        AppSettings.reload()
        assertEquals(BUNDLED, KoAppSettings.deepgramApiKey.value)
        assertEquals(BUNDLED, KoAppSettings.geminiApiKey.value)
    }

    private fun appSettingsField(name: String) =
        AppSettings::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun koField(name: String) =
        KoAppSettings::class.java.getDeclaredField(name).apply { isAccessible = true }

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

    private companion object {
        const val BUNDLED = "test-bundled"
    }
}
