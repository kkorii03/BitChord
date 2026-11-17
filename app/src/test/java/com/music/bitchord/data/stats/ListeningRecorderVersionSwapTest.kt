package com.music.bitchord.data.stats

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Review F1 (11-17). A music-video or cover-video row hands over to its song
 * version mid-play under a new video id. That is one listen: before this fix the
 * recorder saw a new id, reset the playthrough, announced a second Recents start
 * and filed the rest of the listen on a second row, so Home showed the song
 * twice, Replay split its minutes and a play could be counted 0 or 2 times.
 *
 * Ticks are simulated by putting the recorder's last tick eight seconds back
 * (the most one tick may contribute), so no test waits on the clock.
 */
class ListeningRecorderVersionSwapTest {

    private fun statsField(name: String) =
        ListeningStats::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun recorderField(name: String) =
        ListeningRecorder::class.java.getDeclaredField(name).apply { isAccessible = true }

    private val directoryField = statsField("directory")
    private val openField = statsField("open")
    private val dirtyField = statsField("dirty")
    private val originalDirectory = directoryField.get(ListeningStats)
    private lateinit var directory: File

    // Ids that are not 11 characters and rows with an album and artist id, so
    // the recorder never asks the network to fill anything in.
    private val video = Song(
        videoId = "mvCover",
        title = "Cover (video)",
        artist = "Singer",
        thumbnailUrl = null,
        durationText = "1:00",
        artistId = "UCsinger",
        albumName = "Covers",
        isVideo = true,
    )
    private val audio = video.copy(videoId = "auCover", title = "Cover", isVideo = false)

    /** A 60 s track: the play threshold is the 30 s floor. */
    private val lengthMs = 60_000L

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-version-swap").toFile()
        directoryField.set(ListeningStats, directory)
        openField.set(ListeningStats, null)
        dirtyField.setBoolean(ListeningStats, false)
        resetRecorder()
    }

    @After
    fun tearDown() {
        resetRecorder()
        runBlocking<Unit> { ListeningStats.koFlushAndAwait() }
        openField.set(ListeningStats, null)
        dirtyField.setBoolean(ListeningStats, false)
        directoryField.set(ListeningStats, originalDirectory)
        directory.deleteRecursively()
    }

    private fun resetRecorder() {
        ListeningRecorder.onStopped()
        recorderField("startReportedId").set(ListeningRecorder, null)
        recorderField("privatePlaythroughId").set(ListeningRecorder, null)
    }

    /** One sampler tick for [song], eight seconds after the last one. */
    private fun tick(song: Song) {
        recorderField("lastSampleAt").setLong(ListeningRecorder, System.currentTimeMillis() - 8_000L)
        ListeningRecorder.onSample(song, lengthMs)
    }

    private fun openRows(): Map<String, TrackEntry> =
        ListeningStats.koOpenTracks().second.associateBy { it.id }

    @Test
    fun `a playthrough continues across a version swap on one row with one play`() {
        ListeningRecorder.onSample(video, lengthMs) // anchors, contributes nothing
        repeat(3) { tick(video) } // 24 s on the video
        assertEquals(24_000L, openRows().getValue("mvCover").ms)

        ListeningRecorder.onVersionSwapped("mvCover", audio)
        repeat(2) { tick(audio) } // 16 s more: 40 s, past the 30 s floor

        val rows = openRows()
        // Old code: the video row stays (24 s) and the audio row starts over
        // (its first tick only anchors: 8 s, no play).
        assertNull("the video's row must not stay beside the song's", rows["mvCover"])
        val row = rows.getValue("auCover")
        assertEquals("the whole listen is on the song's row", 40_000L, row.ms)
        assertEquals("the listen counts once", 1, row.plays)
        assertEquals("Cover", row.title)
        assertEquals(1, rows.size)
    }

    @Test
    fun `a play already counted before the swap moves and is not counted again`() {
        ListeningRecorder.onSample(video, lengthMs)
        repeat(4) { tick(video) } // 32 s: counted on the video
        assertEquals(1, openRows().getValue("mvCover").plays)

        ListeningRecorder.onVersionSwapped("mvCover", audio)
        repeat(4) { tick(audio) }

        val rows = openRows()
        assertNull(rows["mvCover"])
        assertEquals(64_000L, rows.getValue("auCover").ms)
        assertEquals(1, rows.getValue("auCover").plays)
    }

    @Test
    fun `a swap while paused resumes the same playthrough on the new id`() {
        ListeningRecorder.onSample(video, lengthMs)
        repeat(2) { tick(video) } // 16 s
        ListeningRecorder.onPaused()

        ListeningRecorder.onVersionSwapped("mvCover", audio)
        ListeningRecorder.onSample(audio, lengthMs) // the resume re-anchors
        repeat(2) { tick(audio) }

        val rows = openRows()
        assertNull(rows["mvCover"])
        assertEquals(32_000L, rows.getValue("auCover").ms)
        assertEquals(1, rows.getValue("auCover").plays)
    }

    @Test
    fun `earlier listening of the video keeps its own row`() {
        // An earlier, finished play of the video this month.
        ListeningStats.record(video, 50_000L, countsAsPlay = true)
        ListeningRecorder.onSample(video, lengthMs)
        repeat(2) { tick(video) } // 16 s of this playthrough

        ListeningRecorder.onVersionSwapped("mvCover", audio)
        tick(audio)

        val rows = openRows()
        assertEquals("only this playthrough moves", 50_000L, rows.getValue("mvCover").ms)
        assertEquals(1, rows.getValue("mvCover").plays)
        assertEquals(24_000L, rows.getValue("auCover").ms)
    }

    @Test
    fun `a swap of a track the recorder is not on changes nothing`() {
        ListeningRecorder.onSample(video, lengthMs)
        repeat(2) { tick(video) }

        ListeningRecorder.onVersionSwapped("someoneElse", audio)
        ListeningRecorder.onVersionSwapped("mvCover", video) // same id: not a swap

        val rows = openRows()
        assertEquals(16_000L, rows.getValue("mvCover").ms)
        assertTrue(rows["auCover"] == null)
    }
}
