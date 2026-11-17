package com.music.bitchord.ko

import android.net.FakeUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.playback.CrossfadeController
import com.music.bitchord.playback.mediaItemEntryIds
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review NR2a-1: the armed next row (a video) is swapped for its song during
 * the standby lead. The standby must not be reloaded with the song at the
 * video's mix-in point — that started the song 10-20 s into its first verse.
 * The arm is dropped so the next tick plans the song from its own analysis.
 * A lossless copy of the same recording still re-arms in place.
 */
class KoCrossfadeSwapRearmTest {

    private val cur = item("CUR", "https://yt.example/cur", "entry-1")
    private val video = item("VID", "https://yt.example/vid", "entry-2")
    private val song = item("SONG", "https://yt.example/song", "entry-2")
    private val videoFlac = item("VID", "https://hq.example/vid.flac", "entry-2")

    private val out = FakePlayer(mutableListOf(cur, video), index = 0)
    private val into = FakePlayer(mutableListOf(cur, video), index = 1)
    private val controller = CrossfadeController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        active = { out.player },
        standby = { into.player },
        onHandoff = { _, _ -> },
    )

    private fun armOnVideo(cueMs: Long) {
        controller.phase = CrossfadeController.Phase.ARMING
        controller.handedOff = false
        controller.outgoing = out.player
        controller.incoming = into.player
        controller.ko.incomingItemIndex = 1
        controller.incomingCueTimeMs = cueMs
    }

    @Test
    fun `a song swapped in for the armed video is not re-armed at the video's cue`() {
        armOnVideo(cueMs = 12_000L)
        out.items[1] = song // SongVersionController's replaceMediaItem, same queue entry

        assertTrue(controller.koIncomingQueueChanged())

        assertFalse(
            "standby reloaded with the song at the video's cue: ${into.loads}",
            into.loads.any { it.first == "SONG" },
        )
        assertEquals(CrossfadeController.Phase.IDLE, controller.phase)
        assertFalse(controller.isArming())
        assertEquals("the stale cue is cleared for the re-plan", 0L, controller.incomingCueTimeMs)
    }

    @Test
    fun `rearm refuses a different recording than the one armed`() {
        armOnVideo(cueMs = 12_000L)
        out.items[1] = song

        assertFalse(controller.rearmIncoming(song, 1))
        assertTrue(into.loads.isEmpty())
        assertTrue("nothing torn down", controller.isArming())
    }

    @Test
    fun `a lossless copy of the armed recording still re-arms at its cue`() {
        armOnVideo(cueMs = 12_000L)
        out.items[1] = videoFlac

        assertTrue(controller.rearmIncoming(videoFlac, 1))
        assertEquals(listOf("VID" to 12_000L), into.loads)
        assertTrue(controller.isArming())
    }

    private fun item(id: String, url: String, entry: String): MediaItem =
        MediaItem.Builder().setMediaId(id).setUri(FakeUri(url)).build()
            .also { mediaItemEntryIds[it] = entry }

    /** Just the ExoPlayer surface the arm/rearm/bail paths read and write. */
    private class FakePlayer(var items: MutableList<MediaItem>, var index: Int) {
        val loads = mutableListOf<Pair<String, Long>>()

        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "getMediaItemCount" -> items.size
                    "getMediaItemAt" -> items[args!![0] as Int]
                    "getCurrentMediaItemIndex" -> index
                    "getCurrentMediaItem" -> items.getOrNull(index)
                    "getNextMediaItemIndex" -> if (index + 1 < items.size) index + 1 else -1
                    "getPlayWhenReady" -> true
                    "getVolume" -> 1f
                    "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                    "setMediaItems" -> {
                        val a = args!!
                        if (a.size == 3) {
                            @Suppress("UNCHECKED_CAST")
                            val list = a[0] as List<MediaItem>
                            index = a[1] as Int
                            items = list.toMutableList()
                            loads += list[index].mediaId to (a[2] as Long)
                        }
                        null
                    }
                    "clearMediaItems" -> {
                        items = mutableListOf()
                        index = 0
                        null
                    }
                    "toString" -> "fake-player"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> defaultValue(method.returnType)
                }
            },
        ) as ExoPlayer
    }
}

private fun defaultValue(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> '\u0000'
    else -> null
}
