package com.music.bitchord.data.settings

import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.YouTubeProxy
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The plain proxy route has to reach YouTubeProxy before the encrypted store
 * opens, and the password has to follow once that store is attached.
 */
class AppSettingsProxyInitTest {

    private lateinit var directory: File

    private val prefsField = field("prefs")
    private val contextField = field("appContext")
    private val authField = field("authStore")

    private val originalPrefs = runCatching { prefsField.get(AppSettings) }.getOrNull()
    private val originalContext = runCatching { contextField.get(AppSettings) }.getOrNull()
    private val originalAuth = runCatching { authField.get(AppSettings) }.getOrNull()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-proxy-init").toFile()
    }

    @After
    fun tearDown() {
        if (originalPrefs != null) prefsField.set(AppSettings, originalPrefs)
        if (originalContext != null) contextField.set(AppSettings, originalContext)
        if (originalAuth != null) authField.set(AppSettings, originalAuth)
        YouTubeProxy.update(ProxyConfig())
        directory.deleteRecursively()
    }

    @Test
    fun `the configured route is published before the auth store attaches`() {
        val preferences = FakePreferences(
            mapOf(
                "proxy_mode" to "HTTP",
                "proxy_host" to "proxy.example",
                "proxy_port" to 8080,
                "proxy_scope" to "MUSIC_ONLY",
            ),
        )
        prefsField.set(AppSettings, preferences.prefs)
        contextField.set(AppSettings, FakeContext.create(preferences, directory))
        authField.set(AppSettings, null)

        AppSettings.initProxy(FakeContext.create(preferences, directory))

        assertTrue("a configured proxy must be live before the first browse", YouTubeProxy.config.enabled)
        assertEquals("proxy.example", YouTubeProxy.config.host)
        assertEquals("", YouTubeProxy.config.password)

        attachAuthStore(FakePreferences(mapOf("proxy_password" to "secret")))

        assertEquals("secret", YouTubeProxy.config.password)
    }

    private fun attachAuthStore(preferences: FakePreferences) {
        val attach = AppSettings::class.java.declaredMethods
            .firstOrNull { it.name == "attachAuthStore" }
            ?: error("AppSettings needs an attach point")
        attach.isAccessible = true
        attach.invoke(AppSettings, fakeAuthStore(preferences))
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
