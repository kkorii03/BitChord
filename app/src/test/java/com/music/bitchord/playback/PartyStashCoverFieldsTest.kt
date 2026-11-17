package com.music.bitchord.playback

import android.content.SharedPreferences
import com.music.bitchord.data.StoredSongDisplay
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** [KO] P1-5: leaving a party must give back a cover row's original-song fields. */
class PartyStashCoverFieldsTest {

    private lateinit var prefs: MapPrefs

    @Before
    fun setUp() {
        prefs = MapPrefs()
        PartyPersonalQueueStash.initForTesting(prefs)
    }

    @Test
    fun `a cover row keeps its original song and artist channel across the stash`() {
        val cover = Song(
            videoId = "p15-cover",
            title = "Shape of You",
            artist = "Ed Sheeran · Jane 커버",
            thumbnailUrl = null,
            artistId = "UC-artist-p15",
            channelId = "UC-uploader-p15",
            isVideo = true,
            originalTitle = "Shape of You (Cover by Jane)",
            originalArtist = "Jane",
            coverSinger = "Jane",
            coverOriginalArtist = "Ed Sheeran",
        )
        PartyPersonalQueueStash.stash(listOf(cover), index = 0, positionMs = 0L, wasPlaying = false)

        val restored = PartyPersonalQueueStash.load()!!.songs.single()
        val expected = StoredSongDisplay.clean(cover)

        assertEquals("UC-artist-p15", restored.artistId)
        assertEquals("UC-uploader-p15", restored.channelId)
        assertNotNull(restored.originalTitle)
        assertEquals(expected.originalTitle, restored.originalTitle)
        assertEquals(expected.originalArtist, restored.originalArtist)
        assertEquals(expected.coverSinger, restored.coverSinger)
        assertEquals(expected.coverOriginalArtist, restored.coverOriginalArtist)
    }

    @Test
    fun `a stash written before these fields still decodes`() {
        // KEY_STASH in PartyPersonalQueueStash; a version-2 stash from before P1-5.
        prefs.data["stashed_queue"] =
            """{"version":2,"tracks":[{"id":"p15-old","title":"Old Song","artist":"Old Artist","channelId":"UC-up"}],"index":0,"positionMs":1200}"""

        val snapshot = PartyPersonalQueueStash.load()
        assertNotNull(snapshot)
        val song = snapshot!!.songs.single()
        assertEquals("p15-old", song.videoId)
        assertEquals("UC-up", song.channelId)
        assertNull(song.artistId)
        assertNull(song.coverSinger)
        assertEquals(1200L, snapshot.positionMs)
    }

    private class MapPrefs : SharedPreferences {
        val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            data[key] as? Set<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}

        private class Editor(private val prefs: MapPrefs) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor = put(key, values)
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
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
                if (clearAll) prefs.data.clear()
                removed.forEach { prefs.data.remove(it) }
                prefs.data.putAll(pending)
            }
        }
    }
}
