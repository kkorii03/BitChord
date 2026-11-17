package com.music.bitchord.ko

import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.StoredBucket
import com.music.bitchord.data.stats.TrackEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * F4: Recents is rebuilt twice per track, also with the screen off. A closed
 * month that has not changed must not be read and decoded again, and every
 * real change (a write, a restore, a deleted month) must still show.
 */
class KoListeningStatsRecentMemoTest {

    private val directoryField = field("directory")
    private val openField = field("open")
    private val dirtyField = field("dirty")
    private val importingField = field("importing")
    private val lastWriteField = field("lastWrite")
    private val generationField = field("importGeneration")

    private val originalDirectory = directoryField.get(ListeningStats)
    private lateinit var directory: File

    @Before
    fun setUp() = runBlocking<Unit> {
        directory = Files.createTempDirectory("bitchord-recents-memo").toFile()
        directoryField.set(ListeningStats, directory)
        reset()
        // A restore of nothing into the empty folder: also how the memo is
        // emptied between tests (the restore path forgets it).
        assertTrue(ListeningStats.importAll(emptyList()))
        reset()
    }

    @After
    fun tearDown() {
        reset()
        directoryField.set(ListeningStats, originalDirectory)
        directory.deleteRecursively()
    }

    @Test
    fun `an unchanged closed month is not decoded again on the next refresh`() = runBlocking<Unit> {
        ListeningStats.write("2026-08", bucket("2026-08", track("a", "aaaa", last = 100)), generation())
        ListeningStats.write("2026-09", bucket("2026-09", track("b", "keep", last = 200)), generation())
        val august = File(directory, "2026-08.json")
        val stamp = 1_700_000_000_000L
        august.setLastModified(stamp)

        assertEquals(listOf("keep", "aaaa"), ListeningStats.recentTracks().map { it.title })

        // Same length, same modified time, different bytes: only a re-read can
        // see "bbbb". The old code decoded every closed month on every event.
        val length = august.length()
        august.writeText(august.readText().replace("aaaa", "bbbb"))
        august.setLastModified(stamp)
        assertEquals(length, august.length())

        assertEquals(
            "a closed month whose file is unchanged must come from memory",
            listOf("keep", "aaaa"),
            ListeningStats.recentTracks().map { it.title },
        )
    }

    @Test
    fun `a closed month changed on disk is read again`() = runBlocking<Unit> {
        ListeningStats.write("2026-08", bucket("2026-08", track("a", "old", last = 100)), generation())
        assertEquals(listOf("old"), ListeningStats.recentTracks().map { it.title })

        val august = File(directory, "2026-08.json")
        august.writeText(august.readText().replace("\"old\"", "\"a newer title\""))

        assertEquals(listOf("a newer title"), ListeningStats.recentTracks().map { it.title })
    }

    @Test
    fun `a write through the store is seen even when the file looks unchanged`() = runBlocking<Unit> {
        ListeningStats.write("2026-08", bucket("2026-08", track("a", "aaaa", last = 100)), generation())
        val august = File(directory, "2026-08.json")
        val stamp = 1_700_000_000_000L
        august.setLastModified(stamp)
        val length = august.length()
        assertEquals(listOf("aaaa"), ListeningStats.recentTracks().map { it.title })

        // A rollover/seed write inside the same clock tick on a coarse
        // filesystem: the writer itself has to forget the month.
        ListeningStats.write("2026-08", bucket("2026-08", track("a", "bbbb", last = 100)), generation())
        august.setLastModified(stamp)
        assertEquals(length, august.length())

        assertEquals(listOf("bbbb"), ListeningStats.recentTracks().map { it.title })
    }

    @Test
    fun `a restore and a deleted month both show`() = runBlocking<Unit> {
        ListeningStats.write("2026-07", bucket("2026-07", track("x", "xxxx", last = 50)), generation())
        ListeningStats.write("2026-08", bucket("2026-08", track("a", "aaaa", last = 100)), generation())
        assertEquals(listOf("aaaa", "xxxx"), ListeningStats.recentTracks().map { it.title })

        assertTrue(
            ListeningStats.importAll(listOf(bucket("2026-08", track("r", "rrrr", last = 300)))),
        )
        reset()
        assertEquals(listOf("rrrr"), ListeningStats.recentTracks().map { it.title })

        File(directory, "2026-08.json").delete()
        assertEquals(emptyList<String>(), ListeningStats.recentTracks().map { it.title })
    }

    @Test
    fun `memoised rows are the same rows a full merge gives`() = runBlocking<Unit> {
        // Three months, ties on `last`, an id heard in two months, a blank id,
        // and more rows per month than the memo keeps.
        val months = listOf("2026-06", "2026-07", "2026-08")
        val all = mutableListOf<TrackEntry>()
        months.forEachIndexed { m, key ->
            val rows = (0 until 30).map { i ->
                val id = if (i == 7) "shared" else if (i == 9) " " else "$key-$i"
                track(id, "$key/$i", last = ((i * 37 + m * 11) % 23).toLong())
            }
            all += rows
            ListeningStats.write(key, bucket(key, *rows.toTypedArray()), generation())
        }
        // Twice: the first fills the memo, the second is served from it.
        repeat(2) {
            for (limit in listOf(1, 2, 5, 19, 20, 21, 40)) {
                val expected = KoListeningStatsRecent.mergeRecentTracks(all.map { it.copy() }, emptyList(), limit)
                assertEquals(
                    "limit $limit",
                    expected.map { it.id to it.title },
                    ListeningStats.recentTracks(limit).map { it.id to it.title },
                )
            }
        }
    }

    @Test
    fun `a caller changing a returned row does not change the memo`() = runBlocking<Unit> {
        ListeningStats.write("2026-08", bucket("2026-08", track("a", "aaaa", last = 100)), generation())
        ListeningStats.recentTracks().single().plays = 99
        assertEquals(0, ListeningStats.recentTracks().single().plays)
    }

    private fun track(id: String, title: String, last: Long) = TrackEntry(id = id, title = title, last = last)

    private fun bucket(month: String, vararg tracks: TrackEntry) =
        StoredBucket(month = month, tracks = tracks.toList())

    private fun generation(): Long = generationField.getLong(ListeningStats)

    private fun reset() {
        openField.set(ListeningStats, null)
        dirtyField.setBoolean(ListeningStats, false)
        importingField.setBoolean(ListeningStats, false)
        lastWriteField.set(ListeningStats, null)
    }

    private fun field(name: String) =
        ListeningStats::class.java.getDeclaredField(name).apply { isAccessible = true }
}
