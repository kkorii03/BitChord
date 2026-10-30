package com.music.bitchord.data.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Settings, sources and proxy are three stores; a failure in one must not leave
 * the earlier ones already changed.
 */
class SettingsTransferRollbackTest {

    private lateinit var directory: File
    private lateinit var preferences: FakePreferences

    private val appPrefs = field(AppSettings::class.java, "prefs")
    private val appAuth = field(AppSettings::class.java, "authStore")
    private val sourceContext = field(SourceRegistry::class.java, "appContext")
    private val sourceOpened = field(SourceRegistry::class.java, "opened")

    private val originalAppPrefs = appPrefs.get(AppSettings)
    private val originalAppAuth = appAuth.get(AppSettings)
    private val originalSourceContext = sourceContext.get(SourceRegistry)
    private val originalSourceOpened = sourceOpened.getBoolean(SourceRegistry)

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-settings-rollback").toFile()
        preferences = FakePreferences(mapOf("theme_mode" to "LIGHT", "onboarding_complete" to true))
        appPrefs.set(AppSettings, preferences.prefs)
        appAuth.set(AppSettings, fakeAuthStore(FakePreferences()))
        AppSettings.reload()

        // Opening the source registry is where a restore can fail before it
        // has anything to publish.
        sourceContext.set(SourceRegistry, ThrowingContext.create())
        sourceOpened.setBoolean(SourceRegistry, false)
    }

    @After
    fun tearDown() {
        appPrefs.set(AppSettings, originalAppPrefs)
        appAuth.set(AppSettings, originalAppAuth)
        sourceContext.set(SourceRegistry, originalSourceContext)
        sourceOpened.setBoolean(SourceRegistry, originalSourceOpened)
        directory.deleteRecursively()
    }

    @Test
    fun `a source failure leaves the settings untouched`() = runBlocking {
        assertEquals(ThemeMode.LIGHT, AppSettings.themeMode.value)
        val preview = SettingsTransfer.Preview(
            file = SettingsTransfer.File(),
            settings = mapOf("theme_mode" to "DARK"),
            changedSettings = mapOf("theme_mode" to "DARK"),
            sources = listOf(SourceConfig(kind = SourceKind.ADDON, baseUrl = "https://example.test")),
            sourcesChanged = true,
            proxy = null,
            proxyChanged = false,
            skipped = 0,
        )

        val result = SettingsTransfer.apply(preview)

        assertTrue(result.isFailure)
        assertEquals("LIGHT", preferences.values["theme_mode"])
        assertEquals(ThemeMode.LIGHT, AppSettings.themeMode.value)
    }

    @Test
    fun `a store that mutates and then throws is still rolled back`() = runBlocking {
        // The preference write lands, then [AppSettings.reload] touches the
        // auth store and throws. The old code set its "applied" flag only after
        // the call returned, so this half-applied theme stayed.
        appAuth.set(AppSettings, uninitializedAuthStore())
        val preview = SettingsTransfer.Preview(
            file = SettingsTransfer.File(),
            settings = mapOf("theme_mode" to "DARK"),
            changedSettings = mapOf("theme_mode" to "DARK"),
            sources = emptyList(),
            sourcesChanged = false,
            proxy = null,
            proxyChanged = false,
            skipped = 0,
        )

        val result = SettingsTransfer.apply(preview)

        assertTrue(result.isFailure)
        assertEquals("LIGHT", preferences.values["theme_mode"])
        assertEquals(ThemeMode.LIGHT, AppSettings.themeMode.value)
    }

    private fun field(type: Class<*>, name: String) =
        type.getDeclaredField(name).apply { isAccessible = true }

    private fun uninitializedAuthStore(): AuthStore {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, AuthStore::class.java) as AuthStore
    }

    private fun fakeAuthStore(preferences: FakePreferences): AuthStore {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val store = allocate.invoke(unsafe, AuthStore::class.java) as AuthStore
        AuthStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(store, preferences.prefs)
        return store
    }

    private class ThrowingContext private constructor() : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            error("preferences are unavailable for this test")

        companion object {
            fun create(): Context {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
                return allocate.invoke(unsafe, ThrowingContext::class.java) as Context
            }
        }
    }
}
