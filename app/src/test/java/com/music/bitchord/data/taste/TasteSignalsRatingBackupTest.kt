package com.music.bitchord.data.taste

import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.stats.Backup
import org.junit.After
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A backup's liked/disliked rows have to be the ratings this device actually
 * keeps, not only the taps made since the process started.
 */
class TasteSignalsRatingBackupTest {

    private val fileField = field("file")
    private val loadedField = field("loaded")
    private val tracksField = field("tracks")
    private val dirtyField = field("dirty")
    private val lastWriteOkField = field("lastWriteOk")
    private val scheduledField = field("scheduled")

    private lateinit var directory: File
    private val originalFile = fileField.get(TasteSignals)
    private val originalLoaded = loadedField.getBoolean(TasteSignals)

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-taste-ratings").toFile()
        fileField.set(TasteSignals, File(directory, "taste/signals.json"))
        loadedField.setBoolean(TasteSignals, true)
        dirtyField.setBoolean(TasteSignals, false)
        lastWriteOkField.setBoolean(TasteSignals, true)
        (scheduledField.get(TasteSignals) as kotlinx.coroutines.Job?)?.cancel()
        scheduledField.set(TasteSignals, null)
    }

    @After
    fun tearDown() {
        @Suppress("UNCHECKED_CAST")
        (tracksField.get(TasteSignals) as MutableMap<String, *>).clear()
        (scheduledField.get(TasteSignals) as kotlinx.coroutines.Job?)?.cancel()
        scheduledField.set(TasteSignals, null)
        dirtyField.setBoolean(TasteSignals, false)
        lastWriteOkField.setBoolean(TasteSignals, true)
        fileField.set(TasteSignals, originalFile)
        loadedField.setBoolean(TasteSignals, originalLoaded)
        directory.deleteRecursively()
    }

    @Test
    fun `a failed write is reported by the next await instead of looking saved`() = runBlocking {
        // A regular file where the parent directory belongs makes the write
        // fail without throwing out of the store.
        File(directory, "taste").writeText("not a directory")
        TasteSignals.restoreRatings(liked = listOf("liked-a"), disliked = emptyList())

        assertTrue("the first write must report the failure", !TasteSignals.flushAndAwait())
        assertTrue("a concurrent flush consuming the dirty flag must not report success", !TasteSignals.flushAndAwait())
    }

    @Test
    fun `ratings restored from a backup are readable as the same ratings`() {
        TasteSignals.restoreRatings(liked = listOf("liked-a"), disliked = listOf("disliked-a"))

        assertEquals(listOf("liked-a"), TasteSignals.ratedVideoIds(LikeStatus.LIKE))
        assertEquals(listOf("disliked-a"), TasteSignals.ratedVideoIds(LikeStatus.DISLIKE))
    }

    @Test
    fun `a later restored rating for the same id wins`() {
        TasteSignals.restoreRatings(liked = listOf("same"), disliked = emptyList())
        TasteSignals.restoreRatings(liked = emptyList(), disliked = listOf("same"))

        assertEquals(emptyList<String>(), TasteSignals.ratedVideoIds(LikeStatus.LIKE))
        assertEquals(listOf("same"), TasteSignals.ratedVideoIds(LikeStatus.DISLIKE))
    }


    @Test
    fun `a restore clears ratings the file does not name`() {
        TasteSignals.restoreRatings(liked = listOf("stale"), disliked = emptyList())

        TasteSignals.restoreRatings(liked = emptyList(), disliked = emptyList())

        assertEquals(emptyList<String>(), TasteSignals.ratedVideoIds(LikeStatus.LIKE))
    }

    @Test
    fun `a restored rating survives the size cap even in a full library`() {
        @Suppress("UNCHECKED_CAST")
        val tracks = tracksField.get(TasteSignals) as MutableMap<String, StoredTrack>
        repeat(1_000) { index ->
            tracks["old-$index"] = StoredTrack(id = "old-$index", lastPlayedAt = Long.MAX_VALUE)
        }

        TasteSignals.restoreRatings(liked = listOf("imported"), disliked = emptyList())
        runBlocking { TasteSignals.flushAndAwait() }

        assertTrue(TasteSignals.ratedVideoIds(LikeStatus.LIKE).contains("imported"))
    }

    @Test
    fun `export combines persisted ratings with this session's overrides`() {
        val (liked, disliked) = Backup.mergeRatedIds(
            session = mapOf(
                "session-like" to LikeStatus.LIKE,
                "session-unlike" to LikeStatus.DISLIKE,
                "persisted-like" to LikeStatus.DISLIKE,
            ),
            persistedLiked = listOf("persisted-like", "also-liked"),
            persistedDisliked = listOf("persisted-dislike", "session-like"),
        )

        assertEquals(listOf("also-liked", "session-like"), liked)
        assertEquals(
            listOf("persisted-dislike", "persisted-like", "session-unlike"),
            disliked,
        )
    }


    @Test
    fun `a rating cleared this session is removed from both exported lists`() {
        val (liked, disliked) = Backup.mergeRatedIds(
            session = mapOf(
                "cleared-like" to LikeStatus.INDIFFERENT,
                "cleared-dislike" to LikeStatus.INDIFFERENT,
            ),
            persistedLiked = listOf("cleared-like", "still-liked"),
            persistedDisliked = listOf("cleared-dislike", "still-disliked"),
        )

        assertEquals(listOf("still-liked"), liked)
        assertEquals(listOf("still-disliked"), disliked)
    }

    private fun field(name: String) =
        TasteSignals::class.java.getDeclaredField(name).apply { isAccessible = true }
}
