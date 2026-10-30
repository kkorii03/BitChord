package com.music.bitchord.data.stats

import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.settings.ThemeMode
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
 * If the listening folder refuses its commit, the settings that were already
 * written must go back to the old device's values rather than staying half
 * restored.
 */
class BackupImportRollbackTest {

    private lateinit var directory: File
    private lateinit var preferences: FakePreferences

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
    private val listeningDirectory = field(ListeningStats::class.java, "directory")
    private val listeningOpen = field(ListeningStats::class.java, "open")
    private val listeningDirty = field(ListeningStats::class.java, "dirty")

    private val originalAppPrefs = appSettingsPrefs.get(AppSettings)
    private val originalAppAuth = appSettingsAuth.get(AppSettings)
    private val originalSearchContext = searchContext.get(SearchHistory)
    private val originalSearchPrefs = searchPrefs.get(SearchHistory)
    private val originalSearchOpened = searchOpened.getBoolean(SearchHistory)
    private val originalPlaylistPrefs = playlistPrefs.get(LocalPlaylistStore)
    private val originalTasteFile = tasteFile.get(TasteSignals)
    private val originalTasteLoaded = tasteLoaded.getBoolean(TasteSignals)
    private val originalListeningDirectory = listeningDirectory.get(ListeningStats)

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-backup-rollback").toFile()
        preferences = FakePreferences(mapOf("theme_mode" to "LIGHT", "onboarding_complete" to true))
        val context = FakeContext.create(preferences, directory)

        appSettingsPrefs.set(AppSettings, preferences.prefs)
        appSettingsAuth.set(AppSettings, fakeAuthStore(FakePreferences()))
        AppSettings.reload()

        searchContext.set(SearchHistory, context)
        searchPrefs.set(SearchHistory, preferences.prefs)
        searchOpened.setBoolean(SearchHistory, true)
        SearchHistory.reload()

        playlistPrefs.set(LocalPlaylistStore, preferences.prefs)
        @Suppress("UNCHECKED_CAST")
        (playlistList.get(LocalPlaylistStore) as kotlinx.coroutines.flow.MutableStateFlow<Any?>)
            .value = emptyList<Any?>()

        tasteFile.set(TasteSignals, File(directory, "taste/signals.json"))
        tasteLoaded.setBoolean(TasteSignals, true)
        @Suppress("UNCHECKED_CAST")
        (tasteTracks.get(TasteSignals) as MutableMap<String, *>).clear()

        val listening = File(directory, "listening").apply { mkdirs() }
        listeningDirectory.set(ListeningStats, listening)
        listeningOpen.set(ListeningStats, null)
        listeningDirty.setBoolean(ListeningStats, false)
        val blocked = File(listening, "2026-10.json").apply { mkdirs() }
        File(blocked, "keep").writeText("keep")
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
        listeningDirectory.set(ListeningStats, originalListeningDirectory)
        listeningOpen.set(ListeningStats, null)
        listeningDirty.setBoolean(ListeningStats, false)
        directory.deleteRecursively()
    }

    @Test
    fun `a listening failure rolls the already-applied settings back`() = runBlocking {
        assertEquals(ThemeMode.LIGHT, AppSettings.themeMode.value)
        val text = Backup.encode(
            Backup.BackupFile(
                app = "bitchord",
                version = 2,
                versionName = "test",
                settings = mapOf("theme_mode" to Backup.PrefValue("string", "DARK")),
                listening = listOf(
                    StoredBucket(
                        month = "2026-10",
                        tracks = listOf(TrackEntry(id = "x", title = "restored")),
                    ),
                ),
            ),
        )

        val result = runCatching { Backup.importText(text) }

        assertTrue(result.isFailure)
        assertEquals("settings must not stay from the failed restore", ThemeMode.LIGHT, AppSettings.themeMode.value)
        assertEquals("LIGHT", preferences.values["theme_mode"])
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
