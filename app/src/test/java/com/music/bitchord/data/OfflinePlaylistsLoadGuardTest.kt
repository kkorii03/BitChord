package com.music.bitchord.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The JSON read and the first like must not race: a like that arrives before
 * the startup read finishes waits for it, then saves the stored rows plus the
 * like, instead of writing the empty placeholder over them.
 */
class OfflinePlaylistsLoadGuardTest {

    private val stored = """
        [{"id":"p1","title":"Road","songs":[
          {"videoId":"a","title":"A","artist":"X","localUri":"file:///a.mp3"}
        ]}]
    """.trimIndent()

    @Before
    fun reset() = OfflinePlaylists.resetForTest()

    @After
    fun cleanUp() = OfflinePlaylists.resetForTest()

    @Test
    fun `a like before the stored read finishes keeps the stored list and the like`() {
        val persisted = CountDownLatch(1)
        val saved = java.util.concurrent.atomic.AtomicReference<List<OfflinePlaylist>?>(null)
        OfflinePlaylists.onPersistForTest = { list ->
            saved.set(list)
            persisted.countDown()
        }
        OfflinePlaylists.loadParserForTest = { raw ->
            val savedSong = Song(
                videoId = "a",
                title = "A",
                artist = "X",
                thumbnailUrl = null,
                localUri = "file:///a.mp3",
            )
            if (raw.contains("file:///a.mp3")) {
                listOf(OfflinePlaylist("p1", "Road", listOf(savedSong)))
            } else {
                emptyList()
            }
        }
        val prefs = BlockingPrefs(stored)
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }

        val init = Thread { OfflinePlaylists.init(context, "Liked songs") }
        init.start()
        assertTrue("the startup read should begin", prefs.readStarted.await(5, TimeUnit.SECONDS))

        var likedResult: Boolean? = null
        val song = Song(
            videoId = "b",
            title = "B",
            artist = "Y",
            thumbnailUrl = null,
            localUri = "file:///b.mp3",
        )
        val like = Thread { likedResult = OfflinePlaylists.toggleLike(song) }
        like.start()
        // toggleLike is now waiting on the same load gate as the startup read.
        Thread.sleep(200)

        prefs.release.countDown()
        init.join(5_000)
        like.join(5_000)

        assertEquals(true, likedResult)
        assertTrue("the like must be in the liked set", OfflinePlaylists.isLiked(song))
        assertEquals(
            listOf("file:///b.mp3"),
            OfflinePlaylists.get(OfflinePlaylists.PREFIX + "liked")!!.songs.map { it.localUri },
        )
        assertEquals(
            listOf("file:///a.mp3"),
            OfflinePlaylists.get(OfflinePlaylists.PREFIX + "p1")!!.songs.map { it.localUri },
        )

        assertTrue("the like should be written", persisted.await(5, TimeUnit.SECONDS))
        val uris = saved.get()!!.flatMap { it.songs }.map { it.localUri }
        assertTrue("the stored row was overwritten", "file:///a.mp3" in uris)
        assertTrue("the like was not saved", "file:///b.mp3" in uris)
    }

    private class BlockingPrefs(private val stored: String) : SharedPreferences {
        val readStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun getString(key: String?, defValue: String?): String? {
            readStarted.countDown()
            release.await(10, TimeUnit.SECONDS)
            return stored
        }

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pending[key ?: ""] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this
            override fun clear() = this

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() = Unit
        }

        override fun getAll(): Map<String, *> = emptyMap<String, Any>()
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
