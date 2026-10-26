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
 * The HQ automation default is decided from the preference file as it stood
 * before this launch, not from whether onboarding is complete. A normal update
 * has already finished onboarding, and must still keep the new switches off.
 */
class AppSettingsMigrationReloadTest {

    private lateinit var directory: File
    private lateinit var preferences: FakePreferences
    private lateinit var context: Context

    private val prefsField = field("prefs")
    private val appContextField = field("appContext")
    private val authStoreField = field("authStore")

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-app-settings").toFile()
        preferences = FakePreferences()
        context = FakeContext.create(preferences, directory)
        appContextField.set(AppSettings, context)
        authStoreField.set(AppSettings, fakeAuthStore(FakePreferences()))
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
    fun `a restored value of the wrong type is dropped instead of breaking reload`() {
        AppSettings.importPrefs(
            mapOf(
                "playback_speed" to "fast",
                "repeat_mode" to "all",
                "equalizer_bands" to 3,
            ),
        )

        assertFalse(preferences.values.containsKey("playback_speed"))
        assertFalse(preferences.values.containsKey("repeat_mode"))
        assertFalse(preferences.values.containsKey("equalizer_bands"))
        assertEquals(1.0f, AppSettings.playbackSpeed.value, 0f)
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
