package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.model.Song
import com.music.bitchord.testutil.FakeContext
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The look-ahead resolves the previous row and the next five, while leaving
 * the row that is playing, a cover (which the lookup answers as "no version")
 * and a video-radio row alone.
 */
class SongVersionControllerLookaheadTest {

    @Test
    fun `the next five rows are swapped and covers and video radio stay`() {
        val rows = (0 until 10).map { row(it, videoRadio = it == 6) }
        val items = rows.map { MediaItem.Builder().setMediaId(it.videoId).build() }.toMutableList()
        val byId = rows.associateBy { it.videoId }
        val replaced = mutableMapOf<Int, String>()
        val lookedUp = mutableListOf<String>()

        val controller = SongVersionController(
            context = FakeContext.create(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            currentPlayer = { null },
            currentAudibleMediaId = { "v2" },
            setSwappingMediaId = {},
            onVersionInstalled = {},
            songOf = { byId.getValue(it.mediaId) },
            resolveVersion = { song, _ ->
                lookedUp += song.videoId
                // A cover upload has no catalogue release; production answers null.
                if (song.videoId == "v4") null else song.copy(videoId = "song-${song.videoId}")
            },
        )

        controller.scheduleUpcomingSongVersions(
            player(
                items = items,
                currentIndex = 2,
                onReplace = { index, item ->
                    replaced[index] = item.mediaId
                    items[index] = item
                },
            ),
        )

        assertEquals(
            mapOf(1 to "song-v1", 3 to "song-v3", 5 to "song-v5", 7 to "song-v7"),
            replaced,
        )
        assertEquals(listOf("v1", "v3", "v4", "v5", "v7"), lookedUp)
        assertEquals("the playing row is never replaced", "v2", items[2].mediaId)
        assertEquals("the cover stays its own upload", "v4", items[4].mediaId)
        assertEquals("video radio is not looked up", "v6", items[6].mediaId)
        assertEquals("a row past the next five is not touched", "v8", items[8].mediaId)
    }

    private fun row(index: Int, videoRadio: Boolean = false) = Song(
        videoId = "v$index",
        title = "Song $index",
        artist = "Artist $index",
        thumbnailUrl = null,
        isVideo = true,
        videoRadio = videoRadio,
    )

    private fun player(
        items: MutableList<MediaItem>,
        currentIndex: Int,
        onReplace: (Int, MediaItem) -> Unit,
    ): ExoPlayer {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "getCurrentMediaItem" -> items[currentIndex]
                "getCurrentMediaItemIndex" -> currentIndex
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args?.firstOrNull() as Int]
                "replaceMediaItem" -> {
                    onReplace(args?.get(0) as Int, args[1] as MediaItem)
                    null
                }
                "toString" -> "lookahead-test-player"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        }
        return Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
            handler,
        ) as ExoPlayer
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
}
