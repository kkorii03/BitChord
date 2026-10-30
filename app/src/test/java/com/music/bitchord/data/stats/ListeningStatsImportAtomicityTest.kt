package com.music.bitchord.data.stats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A restore replaces months in place, so the old history has to survive both a
 * failed rename and a write that was already queued when the import started.
 */
class ListeningStatsImportAtomicityTest {

    private val directoryField = field("directory")
    private val openField = field("open")
    private val dirtyField = field("dirty")
    private val importingField = field("importing")
    private val lastWriteField = field("lastWrite")
    private val generationField = field("importGeneration")

    private val originalDirectory = directoryField.get(ListeningStats)
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-listening-import").toFile()
        directoryField.set(ListeningStats, directory)
        openField.set(ListeningStats, null)
        dirtyField.setBoolean(ListeningStats, false)
        importingField.setBoolean(ListeningStats, false)
        lastWriteField.set(ListeningStats, null)
        generationField.setLong(ListeningStats, 0L)
    }

    @After
    fun tearDown() {
        openField.set(ListeningStats, null)
        dirtyField.setBoolean(ListeningStats, false)
        importingField.setBoolean(ListeningStats, false)
        lastWriteField.set(ListeningStats, null)
        generationField.setLong(ListeningStats, 0L)
        directoryField.set(ListeningStats, originalDirectory)
        directory.deleteRecursively()
    }

    @Test
    fun `a failed commit restores the month that was already on disk`() = runBlocking {
        val original = """{"month":"2026-09","tracks":[],"sources":["old"]}"""
        val old = File(directory, "2026-09.json")
        old.writeText(original)

        // A non-empty directory where the second month should be makes the
        // rename and its delete fallback both fail, after the first new month
        // has already been moved into place.
        val blocked = File(directory, "2026-10.json").apply { mkdirs() }
        File(blocked, "keep").writeText("keep")

        val ok = ListeningStats.importAll(
            listOf(
                StoredBucket(month = "2026-09", tracks = listOf(TrackEntry(id = "new", title = "new"))),
                StoredBucket(month = "2026-10", tracks = listOf(TrackEntry(id = "blocked", title = "blocked"))),
            ),
        )

        assertFalse(ok)
        assertTrue("the old month must still be there", old.isFile)
        assertEquals(original, old.readText())
        assertFalse("the first committed month must be rolled back", File(directory, "2026-09.json.bak").exists())
        assertFalse(File(directory, "import.journal").exists())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".importing") })
    }

    @Test
    fun `a write queued before the import cannot overwrite the restored month`() = runBlocking {
        File(directory, "2026-10.json").writeText("""{"month":"2026-10","tracks":[]}""")
        val staleWriter = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stale = staleWriter.launch {
            delay(150)
            File(directory, "2026-10.json").writeText("""{"month":"2026-10","stale":true}""")
        }
        lastWriteField.set(ListeningStats, stale)

        val ok = ListeningStats.importAll(
            listOf(
                StoredBucket(
                    month = "2026-10",
                    tracks = listOf(TrackEntry(id = "x", title = "imported")),
                ),
            ),
        )

        stale.join()
        assertTrue(ok)
        val text = File(directory, "2026-10.json").readText()
        assertTrue("the restored month must win", text.contains("imported"))
        assertFalse("a pre-import write must not land afterwards", text.contains("stale"))
    }

    @Test
    fun `a snapshot captured before an import cannot land after it`() = runBlocking {
        val month = "2026-10"
        val target = File(directory, "$month.json")
        target.writeText("""{"month":"$month","tracks":[{"id":"old","title":"old"}]}""")
        val captured = generationField.getLong(ListeningStats)

        val imported = ListeningStats.importAll(
            listOf(
                StoredBucket(
                    month = month,
                    tracks = listOf(TrackEntry(id = "fresh", title = "fresh")),
                ),
            ),
        )
        assertTrue(imported)

        // This is the job a flush took just before the import flag flipped but
        // launched after the import had already read `lastWrite`.
        ListeningStats.write(
            month,
            StoredBucket(month = month, tracks = listOf(TrackEntry(id = "stale", title = "stale"))),
            captured,
        )
        val text = target.readText()
        assertTrue("the restored month must win", text.contains("fresh"))
        assertFalse("a pre-import snapshot must not land afterwards", text.contains("stale"))

        // A write captured after the import is still applied.
        ListeningStats.write(
            month,
            StoredBucket(month = month, tracks = listOf(TrackEntry(id = "after", title = "after"))),
            generationField.getLong(ListeningStats),
        )
        assertTrue(target.readText().contains("after"))
    }

    @Test
    fun `a rollback that cannot restore a month keeps the journal for the next open`() {
        val original = File(directory, "2026-09.json").apply { mkdirs() }
        File(original, "keep").writeText("keep")
        val backup = File(directory, "2026-09.json.bak")
        backup.writeText("""{"month":"2026-09","tracks":[],"old":true}""")
        val journal = File(directory, "import.journal")
        journal.writeText("O 2026-09.json\n")

        val recovery = ListeningStats::class.java.getDeclaredMethod("recoverInterruptedImport")
            .apply { isAccessible = true }
        recovery.invoke(ListeningStats)

        assertTrue("the old month is still only in the backup", backup.isFile)
        assertTrue("the journal must stay so recovery retries", journal.exists())
    }

    @Test
    fun `an interrupted import is rolled back on the next open`() {
        val old = File(directory, "2026-09.json")
        val backup = File(directory, "2026-09.json.bak")
        val new = File(directory, "2026-09.json.importing")
        File(directory, "import.journal").writeText("O 2026-09.json\nN 2026-09.json\n")
        backup.writeText("""{"month":"2026-09","tracks":[],"old":true}""")
        new.writeText("""{"month":"2026-09","tracks":[],"new":true}""")

        val recovery = ListeningStats::class.java.getDeclaredMethod("recoverInterruptedImport")
            .apply { isAccessible = true }
        recovery.invoke(ListeningStats)

        assertTrue(old.isFile)
        assertTrue(old.readText().contains("old"))
        assertFalse(backup.exists())
        assertFalse(new.exists())
        assertFalse(File(directory, "import.journal").exists())
    }


    @Test
    fun `a rollback that cannot delete a committed month keeps the journal`() {
        // A month this import put in place that cannot be removed (a non-empty
        // directory standing where the file would be) is a failed rollback even
        // when every old `.bak` renamed back. The journal must survive so the
        // next open retries instead of purging the remaining backups.
        val applied = File(directory, "2026-10.json").apply { mkdirs() }
        File(applied, "keep").writeText("keep")
        val old = File(directory, "2026-09.json.bak")
        old.writeText("""{"month":"2026-09","tracks":[],"old":true}""")

        val restored = ListeningStats.restoreBackups(
            backups = listOf(File(directory, "2026-09.json") to old),
            applied = listOf(applied),
        )

        assertFalse(restored)
    }

    @Test
    fun `an import recovers the previous journal before starting a new one`() = runBlocking {
        val original = """{"month":"2026-09","tracks":[],"old":true}"""
        val old = File(directory, "2026-09.json")
        val oldBackup = File(directory, "2026-09.json.bak")
        oldBackup.writeText(original)
        File(directory, "import.journal").writeText("O 2026-09.json\nN 2026-09.json\n")

        // Make the new import fail at commit so the test observes what happened
        // to the old journal instead of the new import's own success path.
        val blocked = File(directory, "2026-10.json").apply { mkdirs() }
        File(blocked, "keep").writeText("keep")

        val ok = ListeningStats.importAll(
            listOf(
                StoredBucket(
                    month = "2026-10",
                    tracks = listOf(TrackEntry(id = "blocked", title = "blocked")),
                ),
            ),
        )

        assertFalse(ok)
        assertTrue("the interrupted month must be restored first", old.isFile)
        assertEquals(original, old.readText())
        assertFalse("a successful rollback clears the recovered journal", File(directory, "import.journal").exists())
    }

    private fun field(name: String) =
        ListeningStats::class.java.getDeclaredField(name).apply { isAccessible = true }
}
