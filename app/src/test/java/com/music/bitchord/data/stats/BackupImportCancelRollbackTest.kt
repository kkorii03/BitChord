package com.music.bitchord.data.stats

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.lyrics.LyricsOverrideFile
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.spotify.PlaylistImportMode
import com.music.bitchord.data.taste.TasteSignals
import com.music.bitchord.ko.KoBackup
import com.music.bitchord.playback.smart.HqVerificationStore
import com.music.bitchord.playback.smart.LyricsTiming
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

/**
 * Review Sa1/Sa2: a restore that is cancelled part-way (the listener leaves
 * Settings while it runs) must still roll every store back, and a restored
 * backup must not carry this install's notification-permission bookkeeping.
 */
class BackupImportCancelRollbackTest {

    private lateinit var directory: File
    private lateinit var preferences: FakePreferences
    private lateinit var importJob: Job
    private var cancelOnHqCommit = false

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
    private val timingStore = field(LyricsTiming::class.java, "store")
    private val hqCached = field(HqVerificationStore::class.java, "cached")

    private val originalAppPrefs = appSettingsPrefs.get(AppSettings)
    private val originalAppAuth = appSettingsAuth.get(AppSettings)
    private val originalSearchContext = searchContext.get(SearchHistory)
    private val originalSearchPrefs = searchPrefs.get(SearchHistory)
    private val originalSearchOpened = searchOpened.getBoolean(SearchHistory)
    private val originalPlaylistPrefs = playlistPrefs.get(LocalPlaylistStore)
    private val originalTasteFile = tasteFile.get(TasteSignals)
    private val originalTasteLoaded = tasteLoaded.getBoolean(TasteSignals)
    private val originalListeningDirectory = listeningDirectory.get(ListeningStats)
    private val originalTimingStore = timingStore.get(LyricsTiming)
    private val originalHqCached = hqCached.get(HqVerificationStore)
    private val originalNotificationAsked = AppSettings.notificationPermissionAsked.value

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-backup-cancel").toFile()
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

        timingStore.set(LyricsTiming, null)
        hqCached.set(HqVerificationStore, null)

        // A directory where a month file belongs makes the listening commit
        // refuse, so the import always ends in the rollback path.
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
        timingStore.set(LyricsTiming, originalTimingStore)
        hqCached.set(HqVerificationStore, originalHqCached)
        AppSettings.notificationPermissionAsked.value = originalNotificationAsked
        directory.deleteRecursively()
    }

    @Test
    fun `a restore cancelled after the lyric stores still rolls them and the playlists back`() =
        runBlocking {
            val overrideFile = File(directory, LyricsOverrideFile.FILE_NAME)
            assertFalse("precondition: no override file before the restore", overrideFile.exists())
            val text = KoBackup.encode(
                KoBackup.BackupFile(
                    app = "bitchord",
                    version = 3,
                    versionName = "test",
                    settings = mapOf("theme_mode" to Backup.PrefValue("string", "DARK")),
                    listening = listOf(
                        StoredBucket(
                            month = "2026-10",
                            tracks = listOf(TrackEntry(id = "x", title = "restored")),
                        ),
                    ),
                    localPlaylists = listOf(KoBackup.BackupPlaylist(id = "p1", title = "restored")),
                    lyricsOverrides = """{"version":1,"songs":{}}""",
                    hqVerified = "row",
                ),
            )
            // The HQ verdict commit is the last write before the listening
            // folder: cancelling there is the listener closing Settings after
            // both lyric stores already hold the backup's copy.
            cancelOnHqCommit = true
            val context = cancellingContext()

            importJob = launch {
                runCatching { KoBackup.importText(text, PlaylistImportMode.REPLACE, context) }
            }
            importJob.join()

            assertTrue("the cancel must have fired", importJob.isCancelled)
            assertFalse(
                "the lyric override file must go back to the device's (absent) copy",
                overrideFile.exists(),
            )
            assertEquals(
                "in-memory playlists must not stay the backup's",
                emptyList<Any?>(),
                LocalPlaylistStore.playlists.value,
            )
            assertEquals(ThemeMode.LIGHT, AppSettings.themeMode.value)
            assertEquals("LIGHT", preferences.values["theme_mode"])
        }

    @Test
    fun `a restored backup cannot mark the notification prompt as already asked`() {
        assertFalse(AppSettings.notificationPermissionAsked.value)

        assertTrue(AppSettings.importPrefs(mapOf("notification_permission_asked" to true)))

        assertFalse(
            "this install never showed the dialog",
            AppSettings.notificationPermissionAsked.value,
        )
        assertNotEquals(true, preferences.values["notification_permission_asked"])
    }

    /**
     * A context whose preference file is [preferences], but whose editor
     * cancels [importJob] when HqVerificationStore commits its verified rows.
     */
    private fun cancellingContext(): Context {
        val fake = preferences.prefs
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "edit") {
                wrapEditor(fake.edit())
            } else {
                method.invoke(fake, *(args ?: arrayOfNulls<Any>(0)))
            }
        } as SharedPreferences
        CancellingContext.prefs = prefs
        CancellingContext.filesDirectory = directory
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, CancellingContext::class.java) as Context
    }

    private fun wrapEditor(inner: SharedPreferences.Editor): SharedPreferences.Editor {
        var putsVerified = false
        lateinit var wrapper: SharedPreferences.Editor
        wrapper = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            if (method.name == "putString" && args?.get(0) == "verified") putsVerified = true
            val result = method.invoke(inner, *(args ?: arrayOfNulls<Any>(0)))
            if (method.name == "commit" && putsVerified && cancelOnHqCommit) {
                cancelOnHqCommit = false
                importJob.cancel()
            }
            if (result === inner) wrapper else result
        } as SharedPreferences.Editor
        return wrapper
    }

    private class CancellingContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs!!

        override fun getFilesDir(): File? = filesDirectory

        companion object {
            var prefs: SharedPreferences? = null
            var filesDirectory: File? = null
        }
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
