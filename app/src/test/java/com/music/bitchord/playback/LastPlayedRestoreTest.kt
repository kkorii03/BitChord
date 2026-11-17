package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.net.SongVersionPolicy
import com.music.bitchord.data.net.UnprovenVersions
import com.music.bitchord.ko.KoConvertedRows
import com.music.bitchord.testutil.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LastPlayedRestoreTest {

    private lateinit var preferences: FakePreferences

    @Before
    fun setUp() {
        preferences = FakePreferences()
        LastPlayed.initForTesting(preferences.prefs)
    }

    @Test
    fun `a queue change does not restore the previous track's playhead`() {
        LastPlayed.saveQueueImmediately(listOf(song("old")), index = 0, positionMs = 123_456L)
        assertEquals(123_456L, LastPlayed.load()!!.positionMs)

        LastPlayed.saveQueue(listOf(song("new-a"), song("new-b")), index = 1, positionMs = 0L)

        val restored = LastPlayed.load()!!
        assertEquals(0L, restored.positionMs)
        assertEquals(1, restored.index)
        assertEquals("new-b", restored.songs[1].videoId)
    }

    @Test
    fun `a queue change keeps the playhead it was handed`() {
        LastPlayed.saveQueue(listOf(song("a"), song("b")), index = 0, positionMs = 4_200L)

        val restored = LastPlayed.load()!!
        assertEquals(4_200L, restored.positionMs)
        assertEquals("a", restored.songs[restored.index].videoId)
    }

    @Test
    fun `a playhead captured before a queue change cannot overwrite the new queue`() {
        LastPlayed.saveQueueImmediately(listOf(song("old")), index = 0, positionMs = 123_456L)
        // The sampler queued this write for the old queue, but the queue edit's
        // snapshot is written before the write gets to run.
        val staleGeneration = LastPlayed.currentQueueGeneration()

        LastPlayed.saveQueue(listOf(song("new-a"), song("new-b")), index = 1, positionMs = 0L)
        LastPlayed.savePlaybackState(
            index = 0,
            positionMs = 123_456L,
            generation = staleGeneration,
        )

        val restored = LastPlayed.load()!!
        assertEquals(0L, restored.positionMs)
        assertEquals(1, restored.index)
        assertEquals("new-b", restored.songs[1].videoId)
    }

    @Test
    fun `a playhead captured after a queue change still advances`() {
        LastPlayed.saveQueue(listOf(song("a"), song("b")), index = 0, positionMs = 4_200L)
        val generation = LastPlayed.currentQueueGeneration()

        LastPlayed.savePlaybackState(index = 1, positionMs = 5_000L, generation = generation)

        val restored = LastPlayed.load()!!
        assertEquals(1, restored.index)
        assertEquals(5_000L, restored.positionMs)
    }

    @Test
    fun `a stored soundtrack queue row is cleaned when it is restored`() {
        val raw = """
            {"version":2,"tracks":[{
              "id":"joker-1",
              "title":"Gary Glitter - Rock & Roll Part II | Joker OST - Original Soundtrack",
              "artist":"Original Soundtrack"
            }],"index":0,"positionMs":0}
        """.trimIndent()
        LastPlayed.initForTesting(FakePreferences(mapOf("queue" to raw)).prefs)

        val restored = LastPlayed.load()!!

        assertEquals("Rock & Roll Part II", restored.songs.single().title)
        assertEquals("Gary Glitter", restored.songs.single().artist)
    }

    @Test
    fun `a row adopted on loose evidence is still checked after a restart`() {
        // SW7-2: the video row ahead in the queue, converted music-only (not
        // video-origin) to a song adopted on loose evidence.
        val video = Song(
            videoId = "sw8-video",
            title = "Song (Official Video)",
            artist = "Singer",
            thumbnailUrl = null,
            isVideo = true,
            queueEntryId = "sw8-entry-converted",
        )
        val converted = Song(
            videoId = "sw8-adopted",
            title = "Song",
            artist = "Singer",
            thumbnailUrl = null,
            isVideoOrigin = false,
            queueEntryId = "sw8-entry-converted",
        )
        // The same song reached as its own row, in another queue entry.
        val ownRow = converted.copy(queueEntryId = "sw8-entry-own")
        KoConvertedRows.note(video)
        UnprovenVersions.note(video, "sw8-adopted")
        LastPlayed.saveQueue(listOf(converted, ownRow), index = 0, positionMs = 0L)

        // A new process: the memory-only maps are empty.
        UnprovenVersions.settle("sw8-adopted")
        KoConvertedRows.clearForTest()

        val restored = LastPlayed.load()!!
        val row = restored.songs[0]
        val source = UnprovenVersions.sourceOf(row.videoId)
        assertEquals("sw8-video", source?.videoId)
        assertTrue(
            SongVersionPolicy.standsInForVideo(
                rowIsVideoOrigin = row.isVideoOrigin,
                rememberedVideoId = null,
                sourceVideoId = source!!.videoId,
                entryVideoId = KoConvertedRows.videoOf(row.queueEntryId),
            ),
        )
        // The listener's own row carried nothing and is not marked.
        assertNull(KoConvertedRows.videoOf(restored.songs[1].queueEntryId))

        UnprovenVersions.settle("sw8-adopted")
        KoConvertedRows.clearForTest()
    }

    private fun song(id: String) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        thumbnailUrl = null,
    )
}
