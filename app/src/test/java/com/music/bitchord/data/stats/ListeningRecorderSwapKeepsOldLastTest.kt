package com.music.bitchord.data.stats

import com.music.bitchord.data.model.Song
import com.music.bitchord.ko.KoListenVersionSwap
import java.io.File
import java.nio.file.Files
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review RG5-2: when the music video had been heard earlier this month, a
 * version swap moved this playthrough's time to the song's row but left the
 * video row's `last` at the swap time, so Recents (ordered by `last`) still
 * showed the video and the song side by side at the top.
 */
class ListeningRecorderSwapKeepsOldLastTest {

    private fun statsField(name: String) =
        ListeningStats::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun recorderField(name: String) =
        ListeningRecorder::class.java.getDeclaredField(name).apply { isAccessible = true }

    private val directoryField = statsField("directory")
    private val openField = statsField("open")
    private val dirtyField = statsField("dirty")
    private val originalDirectory = directoryField.get(ListeningStats)
    private lateinit var directory: File

    private val video = Song(
        videoId = "mvLast",
        title = "Song (video)",
        artist = "Singer",
        thumbnailUrl = null,
        durationText = "1:00",
        artistId = "UCsinger",
        albumName = "Album",
        isVideo = true,
    )
    private val audio = video.copy(videoId = "auLast", title = "Song", isVideo = false)
    private val lengthMs = 60_000L

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-swap-last").toFile()
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

    private fun tick(song: Song) {
        recorderField("lastSampleAt").setLong(ListeningRecorder, System.currentTimeMillis() - 8_000L)
        ListeningRecorder.onSample(song, lengthMs)
    }

    private fun openRows(): Map<String, TrackEntry> =
        ListeningStats.koOpenTracks().second.associateBy { it.id }

    @Test
    fun `the video row heard before goes back to its own last listen`() {
        val now = System.currentTimeMillis()
        val monthStart = YearMonth.now().atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val earlier = maxOf(monthStart + 1L, now - 10 * 60_000L)
        ListeningStats.record(video, 50_000L, countsAsPlay = true, atMs = earlier)

        ListeningRecorder.onSample(video, lengthMs)
        repeat(2) { tick(video) }
        ListeningRecorder.onVersionSwapped("mvLast", audio)
        tick(audio)

        val rows = openRows()
        val old = rows.getValue("mvLast")
        assertEquals("only this playthrough moved", 50_000L, old.ms)
        assertEquals("its last is its own earlier listen", earlier, old.last)
        assertTrue(old.last < rows.getValue("auLast").last)
    }

    @Test
    fun `move restores the given last only on a surviving row`() {
        val tracks = mutableMapOf(
            "mv" to TrackEntry(id = "mv", title = "v", ms = 60_000L, plays = 1, last = 900L),
        )
        assertTrue(KoListenVersionSwap.move(tracks, "mv", audio, 30_000L, false, atMs = 1_000L, fromLastBefore = 100L))
        assertEquals(100L, tracks.getValue("mv").last)

        // A later value than the row's own is not a restore.
        val later = mutableMapOf("mv" to TrackEntry(id = "mv", title = "v", ms = 60_000L, last = 900L))
        KoListenVersionSwap.move(later, "mv", audio, 30_000L, false, atMs = 1_000L, fromLastBefore = 950L)
        assertEquals(900L, later.getValue("mv").last)
    }
}
