package com.music.bitchord.data.spotify

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext

/**
 * An import is a replacement. A save queued before it must finish first, or the
 * older list would land on disk after the restore and silently undo it.
 */
class LocalPlaylistImportOrderingTest {

    private val writerField = LocalPlaylistStore::class.java.getDeclaredField("writer")
        .apply { isAccessible = true }
    private val playlistsField = LocalPlaylistStore::class.java.getDeclaredField("_playlists")
        .apply { isAccessible = true }

    private lateinit var directory: File
    private lateinit var preferences: FakePreferences
    private lateinit var context: Context
    private var originalWriter: Any? = null

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-local-playlists-order").toFile()
        preferences = FakePreferences()
        context = FakeContext.create(preferences, directory)
        LocalPlaylistStore.init(context)
        playlists().value = emptyList()
        originalWriter = writerField.get(LocalPlaylistStore)
    }

    @After
    fun tearDown() {
        playlists().value = emptyList()
        originalWriter?.let { setStatic(writerField, it) }
        directory.deleteRecursively()
    }

    @Test
    fun `a save queued before an import cannot replace the restored playlists`() = runBlocking {
        val dispatcher = PausedDispatcher()
        setStatic(writerField, CoroutineScope(SupervisorJob() + dispatcher))

        LocalPlaylistStore.savePlaylist(
            "Stale",
            listOf(Song(videoId = "stale", title = "Stale", artist = "A", thumbnailUrl = null)),
        )
        val restored = LocalPlaylist(
            id = "sp_local_restored",
            title = "Restored",
            songs = listOf(Song(videoId = "fresh", title = "Fresh", artist = "A", thumbnailUrl = null)),
        )

        val result = async(Dispatchers.Default) {
            LocalPlaylistStore.importAll(listOf(restored), PlaylistImportMode.REPLACE)
        }
        // The import must still be waiting for the save ahead of it, not already
        // reporting success while that save is queued.
        delay(50)
        assertFalse("the import must wait for the writer", result.isCompleted)
        dispatcher.drain()
        assertEquals(1, result.await())

        assertEquals("Restored", LocalPlaylistStore.playlists.value.single().title)
    }

    @Suppress("UNCHECKED_CAST")
    private fun playlists() =
        playlistsField.get(LocalPlaylistStore) as MutableStateFlow<List<LocalPlaylist>>

    private fun setStatic(field: Field, value: Any?) {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, field)
        unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(unsafe, base, offset, value)
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val queued = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.add(block)
        }

        fun drain() {
            while (true) {
                val next = queued.poll() ?: return
                next.run()
            }
        }
    }
}
