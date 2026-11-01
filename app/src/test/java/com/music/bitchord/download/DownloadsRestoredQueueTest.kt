package com.music.bitchord.download

import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The queue written before a process death has to survive an enqueue that wins
 * the race against the startup restore, and a restored ordinary row must not
 * start on a metered connection just because nobody was left to ask.
 */
class DownloadsRestoredQueueTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private val originalWifiOnly = AppSettings.wifiOnlyDownloads.value
    private val originalMetered = AppSettings.meteredConnection.value

    private val prefsField = field("prefs")
    private val pendingField = field("pending")
    private val runningField = field("running")
    private val restoredField = field("pendingRestored")
    private val meteredAllowedField = field("meteredAllowedIds")
    private val cacheServableField = field("cacheServableIds")

    @Suppress("UNCHECKED_CAST")
    private val pending = pendingField.get(Downloads) as MutableMap<String, Song>

    @Suppress("UNCHECKED_CAST")
    private val running = runningField.get(Downloads) as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private val meteredAllowed = meteredAllowedField.get(Downloads) as MutableSet<String>

    @Suppress("UNCHECKED_CAST")
    private val cacheServable = cacheServableField.get(Downloads) as MutableSet<String>

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        prefsField.set(Downloads, fakePrefs)
        pending.clear()
        running.clear()
        meteredAllowed.clear()
        cacheServable.clear()
        restoredField.setBoolean(Downloads, false)
    }

    @After
    fun tearDown() {
        pending.clear()
        running.clear()
        meteredAllowed.clear()
        cacheServable.clear()
        restoredField.setBoolean(Downloads, false)
        AppSettings.wifiOnlyDownloads.value = originalWifiOnly
        AppSettings.meteredConnection.value = originalMetered
    }

    @Test
    fun `an enqueue before the startup restore does not erase the saved queue`() {
        val saved = song("saved")
        fakePrefs.edit()
            .putString(
                PendingDownloadsStore.key(),
                PendingDownloadsStore.encode(
                    listOf(PendingDownloadsStore.PendingEntry(PendingDownloadsStore.storedSong(saved))),
                ),
            )
            .apply()
        // The foreground event won the race: the new row is already in the
        // in-memory queue while the saved row has not been read back yet.
        pending["new"] = song("new")

        persistPendingLocked()

        val ids = PendingDownloadsStore.decode(fakePrefs.getString(PendingDownloadsStore.key(), null))
            .map { it.song.id }
            .toSet()
        assertEquals(setOf("saved", "new"), ids)
        // Restoring before the write is what keeps the old row from being
        // erased by an enqueue that arrived first.
        assertTrue(pending.keys.containsAll(listOf("saved", "new")))
    }

    @Test
    fun `a restored row waits on a metered connection instead of using data`() {
        AppSettings.wifiOnlyDownloads.value = true
        AppSettings.meteredConnection.value = true
        pending["saved"] = song("saved")

        assertNull(Downloads.takeNext())
        assertTrue(pending.containsKey("saved"))
    }

    @Test
    fun `a row the listener allowed on cellular still starts while metered`() {
        AppSettings.wifiOnlyDownloads.value = true
        AppSettings.meteredConnection.value = true
        val allowed = song("allowed")
        fakePrefs.edit()
            .putString(
                PendingDownloadsStore.key(),
                PendingDownloadsStore.encode(
                    listOf(
                        PendingDownloadsStore.PendingEntry(
                            song = PendingDownloadsStore.storedSong(allowed),
                            allowMetered = true,
                        ),
                    ),
                ),
            )
            .apply()

        restorePendingLocked()

        assertEquals("allowed", Downloads.takeNext()?.videoId)
    }

    @Test
    fun `an allowed connection starts the restored row`() {
        AppSettings.wifiOnlyDownloads.value = true
        AppSettings.meteredConnection.value = false
        pending["saved"] = song("saved")

        assertEquals("saved", Downloads.takeNext()?.videoId)
    }

    @Test
    fun `a restored row the song cache can serve still starts while metered`() {
        AppSettings.wifiOnlyDownloads.value = true
        AppSettings.meteredConnection.value = true
        pending["cached"] = song("cached")
        cacheServable += "cached"
        persistPendingLocked()

        // The process died: nothing is in memory, and the saved row has to
        // bring back the fact that the cache can serve it without data.
        pending.clear()
        cacheServable.clear()
        restoredField.setBoolean(Downloads, false)
        restorePendingLocked()

        assertEquals("cached", Downloads.takeNext()?.videoId)
    }

    @Test
    fun `a restore before prefs is ready does not mark the saved queue read`() {
        val method = Downloads::class.java
            .getDeclaredMethod("restorePendingLocked")
            .apply { isAccessible = true }
        val savedPrefs = prefsField.get(Downloads)
        prefsField.set(Downloads, null)
        try {
            restoredField.setBoolean(Downloads, false)

            method.invoke(Downloads)

            assertFalse(
                "a later restore must still be able to read the saved queue",
                restoredField.getBoolean(Downloads),
            )
        } finally {
            prefsField.set(Downloads, savedPrefs)
        }
    }

    private fun restorePendingLocked() {
        Downloads::class.java
            .getDeclaredMethod("restorePendingLocked")
            .apply { isAccessible = true }
            .invoke(Downloads)
    }

    private fun persistPendingLocked() {
        Downloads::class.java
            .getDeclaredMethod("persistPendingLocked")
            .apply { isAccessible = true }
            .invoke(Downloads)
    }

    private fun song(id: String) = Song(videoId = id, title = "Title", artist = "Artist", thumbnailUrl = null)

    private fun field(name: String) = Downloads::class.java
        .getDeclaredField(name)
        .apply { isAccessible = true }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            data[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            data[key] as? Set<String> ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Number)?.toInt() ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Number)?.toLong() ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (data[key] as? Number)?.toFloat() ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            data[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}

        private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removed.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) {
                    prefs.data.clear()
                }
                for (key in removed) {
                    prefs.data.remove(key)
                }
                prefs.data.putAll(pending)
            }
        }
    }
}
