package com.music.bitchord.data.stats

import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.taste.TasteSignals
import com.music.bitchord.testutil.FakeContext
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
 * A restore that cannot write the local playlists has to name that section the
 * same way every screen does. The failure is caught and shown by
 * SettingsSheet through FriendlyError, which passes Korean text through
 * unchanged, so this exception message is user-visible copy.
 */
class BackupPlaylistFailureMessageTest {

    private lateinit var directory: File
    private lateinit var settingsPreferences: FakePreferences
    private lateinit var playlistPreferences: FakePreferences

    private val appSettingsPrefs = field(AppSettings::class.java, "prefs")
    private val appSettingsAuth = field(AppSettings::class.java, "authStore")
    private val searchContext = field(SearchHistory::class.java, "appContext")
    private val searchPrefs = field(SearchHistory::class.java, "prefs")
    private val searchOpened = field(SearchHistory::class.java, "opened")
    private val playlistPrefs = field(LocalPlaylistStore::class.java, "prefs")
    private val playlistList = field(LocalPlaylistStore::class.java, "_playlists")
    private val tasteFile = field(TasteSignals::class.java, "file")
    private val tasteLoaded = field(TasteSignals::class.java, "loaded")
    private val tasteTracks = field(TasteSignals::class.java, "tracks")

    private val originalAppPrefs = appSettingsPrefs.get(AppSettings)
    private val originalAppAuth = appSettingsAuth.get(AppSettings)
    private val originalSearchContext = searchContext.get(SearchHistory)
    private val originalSearchPrefs = searchPrefs.get(SearchHistory)
    private val originalSearchOpened = searchOpened.getBoolean(SearchHistory)
    private val originalPlaylistPrefs = playlistPrefs.get(LocalPlaylistStore)
    private val originalTasteFile = tasteFile.get(TasteSignals)
    private val originalTasteLoaded = tasteLoaded.getBoolean(TasteSignals)

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-backup-copy").toFile()
        settingsPreferences = FakePreferences(mapOf("theme_mode" to "LIGHT"))
        playlistPreferences = FakePreferences().apply { failCommits = true }
        val context = FakeContext.create(settingsPreferences, directory)

        appSettingsPrefs.set(AppSettings, settingsPreferences.prefs)
        appSettingsAuth.set(AppSettings, fakeAuthStore(FakePreferences()))
        AppSettings.reload()

        searchContext.set(SearchHistory, context)
        searchPrefs.set(SearchHistory, settingsPreferences.prefs)
        searchOpened.setBoolean(SearchHistory, true)
        SearchHistory.reload()

        playlistPrefs.set(LocalPlaylistStore, playlistPreferences.prefs)
        @Suppress("UNCHECKED_CAST")
        (playlistList.get(LocalPlaylistStore) as kotlinx.coroutines.flow.MutableStateFlow<Any?>)
            .value = emptyList<Any?>()

        tasteFile.set(TasteSignals, File(directory, "taste/signals.json"))
        tasteLoaded.setBoolean(TasteSignals, true)
        @Suppress("UNCHECKED_CAST")
        (tasteTracks.get(TasteSignals) as MutableMap<String, *>).clear()
    }

    @After
    fun tearDown() {
        appSettingsPrefs.set(AppSettings, originalAppPrefs)
        appSettingsAuth.set(AppSettings, originalAppAuth)
        searchContext.set(SearchHistory, originalSearchContext)
        searchPrefs.set(SearchHistory, originalSearchPrefs)
        searchOpened.setBoolean(SearchHistory, originalSearchOpened)
        playlistPrefs.set(LocalPlaylistStore, originalPlaylistPrefs)
        @Suppress("UNCHECKED_CAST")
        (playlistList.get(LocalPlaylistStore) as kotlinx.coroutines.flow.MutableStateFlow<Any?>)
            .value = emptyList<Any?>()
        tasteFile.set(TasteSignals, originalTasteFile)
        tasteLoaded.setBoolean(TasteSignals, originalTasteLoaded)
        @Suppress("UNCHECKED_CAST")
        (tasteTracks.get(TasteSignals) as MutableMap<String, *>).clear()
        directory.deleteRecursively()
    }

    @Test
    fun `a playlist write failure names the section the playlist term`() = runBlocking {
        val text = Backup.encode(
            Backup.BackupFile(
                app = "bitchord",
                version = 2,
                versionName = "test",
                settings = mapOf("theme_mode" to Backup.PrefValue("string", "DARK")),
            ),
        )

        val result = runCatching { Backup.importText(text) }

        assertTrue(result.isFailure)
        assertEquals(
            "로컬 플레이리스트를 저장하지 못했습니다",
            result.exceptionOrNull()?.message,
        )
    }

    private fun field(type: Class<*>, name: String) =
        type.getDeclaredField(name).apply { isAccessible = true }

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
