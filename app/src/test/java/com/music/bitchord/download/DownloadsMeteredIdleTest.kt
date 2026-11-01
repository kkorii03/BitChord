package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A queue that is waiting for a connection the listener allows is not work in
 * flight.
 *
 * The download service only stops a worker once [Downloads.busy] is false after
 * the start-of-queue grace. Counting rows held back for metered data as busy
 * kept the foreground service, and its notification, alive for as long as the
 * phone stayed on cellular. The modern equivalent of the old wake path is the
 * network transition in [com.music.bitchord.BitChordApplication], so stopping
 * here loses nothing.
 */
class DownloadsMeteredIdleTest {

    private val originalWifiOnly = AppSettings.wifiOnlyDownloads.value
    private val originalMetered = AppSettings.meteredConnection.value

    @Suppress("UNCHECKED_CAST")
    private val pending = field("pending").get(Downloads) as MutableMap<String, Song>

    @Suppress("UNCHECKED_CAST")
    private val running = field("running").get(Downloads) as MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private val meteredAllowed = field("meteredAllowedIds").get(Downloads) as MutableSet<String>

    @Suppress("UNCHECKED_CAST")
    private val cacheServable = field("cacheServableIds").get(Downloads) as MutableSet<String>

    @Before
    fun setUp() {
        pending.clear()
        running.clear()
        meteredAllowed.clear()
        cacheServable.clear()
        AppSettings.wifiOnlyDownloads.value = true
        AppSettings.meteredConnection.value = true
    }

    @After
    fun tearDown() {
        pending.clear()
        running.clear()
        meteredAllowed.clear()
        cacheServable.clear()
        AppSettings.wifiOnlyDownloads.value = originalWifiOnly
        AppSettings.meteredConnection.value = originalMetered
    }

    @Test
    fun `a row held back for metered data does not keep the worker alive`() {
        pending["waiting"] = song("waiting")

        assertFalse(Downloads.busy())
    }

    @Test
    fun `a row the listener allowed on cellular is still busy`() {
        pending["allowed"] = song("allowed")
        meteredAllowed += "allowed"

        assertTrue(Downloads.busy())
    }

    @Test
    fun `a cache servable row is still busy`() {
        pending["cached"] = song("cached")
        cacheServable += "cached"

        assertTrue(Downloads.busy())
    }

    @Test
    fun `a running download is still busy`() {
        running["running"] = null

        assertTrue(Downloads.busy())
    }

    @Test
    fun `a row on an allowed connection is still busy`() {
        AppSettings.meteredConnection.value = false
        pending["waiting"] = song("waiting")

        assertTrue(Downloads.busy())
    }

    private fun song(id: String) = Song(videoId = id, title = "Title", artist = "Artist", thumbnailUrl = null)

    private fun field(name: String) = Downloads::class.java
        .getDeclaredField(name)
        .apply { isAccessible = true }
}
