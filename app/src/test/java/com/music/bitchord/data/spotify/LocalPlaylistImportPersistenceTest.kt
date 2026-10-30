package com.music.bitchord.data.spotify

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A backup restore must not report success before the playlist file was
 * written, and a failed write must leave the previous library untouched.
 */
class LocalPlaylistImportPersistenceTest {

    private val prefsField = LocalPlaylistStore::class.java.getDeclaredField("prefs")
        .apply { isAccessible = true }
    private val playlistsField = LocalPlaylistStore::class.java.getDeclaredField("_playlists")
        .apply { isAccessible = true }

    private lateinit var directory: File
    private lateinit var preferences: FakePreferences
    private lateinit var context: Context

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-local-playlists").toFile()
        preferences = FakePreferences()
        context = FakeContext.create(preferences, directory)
        LocalPlaylistStore.init(context)
    }

    @After
    fun tearDown() {
        @Suppress("UNCHECKED_CAST")
        (playlistsField.get(LocalPlaylistStore) as kotlinx.coroutines.flow.MutableStateFlow<List<LocalPlaylist>>)
            .value = emptyList()
        directory.deleteRecursively()
    }

    @Test
    fun `a failed commit is reported instead of being called a restore`() = runBlocking {
        preferences.failCommits = true
        val incoming = LocalPlaylist(
            id = "sp_local_1",
            title = "Restored",
            songs = listOf(Song(videoId = "v1", title = "Song", artist = "Artist", thumbnailUrl = null)),
        )

        val result = LocalPlaylistStore.importAll(listOf(incoming), PlaylistImportMode.REPLACE)

        assertEquals(-1, result)
        assertTrue(LocalPlaylistStore.playlists.value.isEmpty())
    }
}
