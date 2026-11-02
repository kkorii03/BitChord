package com.music.bitchord.data.settings

import com.music.bitchord.auth.AuthStore
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Application.onCreate must be able to read plain settings without the
 * Keystore; the encrypted secrets are attached afterwards, from the startup
 * thread, and must then reach the flows they belong to.
 */
class AppSettingsAuthAttachTest {

    private lateinit var directory: File
    private lateinit var preferences: FakePreferences

    private val prefsField = field("prefs")
    private val contextField = field("appContext")
    private val authField = field("authStore")

    private val originalPrefs = runCatching { prefsField.get(AppSettings) }.getOrNull()
    private val originalContext = runCatching { contextField.get(AppSettings) }.getOrNull()
    private val originalAuth = runCatching { authField.get(AppSettings) }.getOrNull()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-auth-attach").toFile()
        preferences = FakePreferences()
        prefsField.set(AppSettings, preferences.prefs)
        contextField.set(AppSettings, FakeContext.create(preferences, directory))
        authField.set(AppSettings, null)
    }

    @After
    fun tearDown() {
        if (originalPrefs != null) prefsField.set(AppSettings, originalPrefs)
        if (originalContext != null) contextField.set(AppSettings, originalContext)
        if (originalAuth != null) authField.set(AppSettings, originalAuth)
        directory.deleteRecursively()
    }

    @Test
    fun `settings load before auth and the attached store supplies the secrets`() {
        assertFalse("plain settings must not need the encrypted store", AppSettings.authReady())

        val authPreferences = FakePreferences(mapOf("lastfm_session_key" to "saved-key"))
        val attach = AppSettings::class.java.declaredMethods
            .firstOrNull { it.name == "attachAuthStore" }
        assertNotNull("AppSettings needs an attach point for the startup-thread auth open", attach)
        attach!!.isAccessible = true
        attach.invoke(AppSettings, fakeAuthStore(authPreferences))

        assertTrue(AppSettings.authReady())
        assertEquals("saved-key", AppSettings.lastfmSessionKey.value)
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
