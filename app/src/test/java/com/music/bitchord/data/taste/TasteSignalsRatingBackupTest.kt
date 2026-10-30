package com.music.bitchord.data.taste

import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.stats.Backup
import org.junit.After
import org.junit.Assert.assertEquals
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

    private lateinit var directory: File
    private val originalFile = fileField.get(TasteSignals)
    private val originalLoaded = loadedField.getBoolean(TasteSignals)

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("bitchord-taste-ratings").toFile()
        fileField.set(TasteSignals, File(directory, "taste/signals.json"))
        loadedField.setBoolean(TasteSignals, true)
    }

    @After
    fun tearDown() {
        @Suppress("UNCHECKED_CAST")
        (tracksField.get(TasteSignals) as MutableMap<String, *>).clear()
        fileField.set(TasteSignals, originalFile)
        loadedField.setBoolean(TasteSignals, originalLoaded)
        directory.deleteRecursively()
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

    private fun field(name: String) =
        TasteSignals::class.java.getDeclaredField(name).apply { isAccessible = true }
}
