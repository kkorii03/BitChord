package com.music.bitchord.data.settings

import com.music.bitchord.auth.AuthStore
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A fresh install's music-only default is a value, not just a computed answer:
 * onboarding writes its own flag in the same launch, so the next start must not
 * mistake the now non-empty file for an existing install and turn it off.
 */
class AppSettingsFreshInstallDefaultTest {

    private val prefsField = field("prefs")
    private val contextField = field("appContext")
    private val authField = field("authStore")

    private val originalPrefs = runCatching { prefsField.get(AppSettings) }.getOrNull()
    private val originalContext = runCatching { contextField.get(AppSettings) }.getOrNull()
    private val originalAuth = runCatching { authField.get(AppSettings) }.getOrNull()

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-fresh-settings").toFile()
    }

    @After
    fun tearDown() {
        if (originalPrefs != null) prefsField.set(AppSettings, originalPrefs)
        if (originalContext != null) contextField.set(AppSettings, originalContext)
        if (originalAuth != null) authField.set(AppSettings, originalAuth)
        directory.deleteRecursively()
    }

    @Test
    fun `a fresh install persists the music-only default before onboarding writes its flag`() {
        val preferences = FakePreferences()
        val context = FakeContext.create(preferences, directory)
        prefsField.set(AppSettings, preferences.prefs)
        contextField.set(AppSettings, context)
        authField.set(AppSettings, fakeAuthStore(FakePreferences()))

        AppSettings.reload()

        assertTrue("the chosen default must be stored", preferences.values.containsKey("prefer_music_only"))
        assertTrue(preferences.values["prefer_music_only"] as Boolean)

        // The next launch sees a non-empty file; the stored value is what keeps
        // the feature on instead of re-defaulting an absent key to false.
        preferences.values["onboarding_complete"] = true
        AppSettings.reload()
        assertTrue(AppSettings.preferMusicOnly.value)
    }

    private fun field(name: String) =
        AppSettings::class.java.getDeclaredField(name).apply { isAccessible = true }

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
