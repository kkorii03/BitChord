package com.music.bitchord.ko

import android.net.FakeUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.playback.mediaItemEntryIds
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review V-3: a version swap fills the standby from a copy of the queue, waits
 * up to 15 s and then adopts it, clearing the old player. Edits made to the live
 * queue in that window ("play next", autoplay, a next row turned from MV into
 * its song) were lost or reverted. The standby now catches up right before the
 * adoption.
 */
class KoSwapQueueCatchUpTest {

    private val a = item("A", "https://yt.example/a", "e-a")
    private val cur = item("CUR", "https://yt.example/cur-mv", "e-cur")
    private val curSong = item("CURSONG", "https://yt.example/cur-song", "e-cur")
    private val next = item("NEXT", "https://yt.example/next-mv", "e-next")
    private val nextSong = item("NEXTSONG", "https://yt.example/next-song", "e-next")
    private val added = item("ADDED", "https://yt.example/added", "e-added")

    @Test
    fun `a song added after the current row during the swap survives the adoption`() {
        val active = FakePlayer(mutableListOf(a, cur, next, added), index = 1)
        val standby = FakePlayer(mutableListOf(a, curSong, next), index = 1)

        val edits = koCatchUpStandbyQueue(active.player, standby.player, 1)

        assertEquals(true, edits?.tail)
        assertEquals(false, edits?.head)
        assertEquals(listOf("A", "CURSONG", "NEXT", "ADDED"), standby.items.map { it.mediaId })
        assertSame("the swapped-in current row is untouched", curSong, standby.items[1])
    }

    @Test
    fun `a next row turned from MV into its song during the swap is not reverted`() {
        val active = FakePlayer(mutableListOf(cur, nextSong), index = 0)
        val standby = FakePlayer(mutableListOf(curSong, next), index = 0)

        koCatchUpStandbyQueue(active.player, standby.player, 0)

        assertEquals(listOf("CURSONG", "NEXTSONG"), standby.items.map { it.mediaId })
    }

    @Test
    fun `an unchanged queue is left alone and the listener's modes are carried`() {
        val active = FakePlayer(mutableListOf(a, cur, next), index = 1, repeat = Player.REPEAT_MODE_ALL, shuffle = true)
        val standby = FakePlayer(mutableListOf(a, curSong, next), index = 1)

        val edits = koCatchUpStandbyQueue(active.player, standby.player, 1)

        assertEquals(false, edits?.head)
        assertEquals(false, edits?.tail)
        assertEquals(0, standby.replaces)
        assertEquals(Player.REPEAT_MODE_ALL, standby.repeat)
        assertTrue(standby.shuffle)
    }

    @Test
    fun `a standby that ran off the swapped row is not touched and not adoptable`() {
        val active = FakePlayer(mutableListOf(cur, next, added), index = 0)
        val standby = FakePlayer(mutableListOf(curSong, next), index = 1)

        assertNull(koCatchUpStandbyQueue(active.player, standby.player, 0))
        assertEquals(0, standby.replaces)
        assertEquals(listOf("CURSONG", "NEXT"), standby.items.map { it.mediaId })
    }

    @Test
    fun `the version swap catches its standby up before adopting it`() {
        val body = source("app/src/main/java/com/music/bitchord/playback/PlaybackService.kt")
            .substringAfter("private fun smoothSwapCurrentTrackVersion(")
            .substringBefore("private val versionAlignmentMeasurements")
        val adopt = body.indexOf("adoptPlayerForVersionSwap(")
        assertTrue("the swap still adopts", adopt > 0)
        val catchUp = body.indexOf("koCatchUpStandbyQueue(")
        assertTrue("catch-up must run before the adoption", catchUp in 0 until adopt)
        assertFalse(
            "nothing may suspend between the catch-up and the adoption",
            "delay(" in body.substring(catchUp, adopt),
        )
    }

    private fun item(id: String, url: String, entry: String): MediaItem =
        MediaItem.Builder().setMediaId(id).setUri(FakeUri(url)).build()
            .also { mediaItemEntryIds[it] = entry }

    private fun source(relative: String): String {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }
        return File(directory, relative).readText().replace("\r\n", "\n")
    }

    /** Just the ExoPlayer surface the catch-up reads and writes. */
    private class FakePlayer(
        var items: MutableList<MediaItem>,
        var index: Int,
        var repeat: Int = Player.REPEAT_MODE_OFF,
        var shuffle: Boolean = false,
    ) {
        var replaces = 0

        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "getMediaItemCount" -> items.size
                    "getMediaItemAt" -> items[args!![0] as Int]
                    "getCurrentMediaItemIndex" -> index
                    "getCurrentMediaItem" -> items.getOrNull(index)
                    "getRepeatMode" -> repeat
                    "setRepeatMode" -> { repeat = args!![0] as Int; null }
                    "getShuffleModeEnabled" -> shuffle
                    "setShuffleModeEnabled" -> { shuffle = args!![0] as Boolean; null }
                    "replaceMediaItems" -> {
                        val from = args!![0] as Int
                        val to = args[1] as Int
                        @Suppress("UNCHECKED_CAST")
                        val list = args[2] as List<MediaItem>
                        val before = items.subList(0, from).toList()
                        val after = items.subList(to, items.size).toList()
                        if (index >= to) index += list.size - (to - from)
                        items = (before + list + after).toMutableList()
                        replaces++
                        null
                    }
                    "toString" -> "fake-player"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> swapCatchUpDefault(method.returnType)
                }
            },
        ) as ExoPlayer
    }
}

private fun swapCatchUpDefault(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    else -> null
}
