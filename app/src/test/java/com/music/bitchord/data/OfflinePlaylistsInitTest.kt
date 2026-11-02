package com.music.bitchord.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The offline-playlist file is read on the startup thread, not in Application.onCreate. */
class OfflinePlaylistsInitTest {

    @Test
    fun `init does not wait for the stored JSON read`() {
        val prefs = BlockingPrefs()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }

        val init = Thread { OfflinePlaylists.init(context, "Liked songs") }
        init.start()
        init.join(1_000)
        val blocked = init.isAlive

        // Let the startup read through even when the assertion is about to fail,
        // so a red run does not leave a blocked executor thread behind.
        prefs.release.countDown()
        prefs.readStarted.await(5, TimeUnit.SECONDS)
        init.join(5_000)

        assertFalse("Application.onCreate must not parse offline playlists", blocked)
        assertTrue("the startup thread should still do the read", prefs.readStarted.count == 0L)
    }

    private class BlockingPrefs : SharedPreferences {
        val readStarted = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun getString(key: String?, defValue: String?): String? {
            readStarted.countDown()
            release.await(10, TimeUnit.SECONDS)
            return defValue
        }

        override fun getAll(): Map<String, *> = emptyMap<String, Any>()
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = error("not used")
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
