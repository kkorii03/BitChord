package com.music.bitchord.data.taste

import com.music.bitchord.data.model.LikeStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A backup only carried the session map before, so a restore disappeared on the
 * next launch. These cover the durable half the backup now reads and writes.
 */
class TasteSignalsRatingsBackupTest {

    private val fileField = TasteSignals::class.java.getDeclaredField("file").apply {
        isAccessible = true
    }
    private val loadedField = TasteSignals::class.java.getDeclaredField("loaded").apply {
        isAccessible = true
    }
    private val tracksField = TasteSignals::class.java.getDeclaredField("tracks").apply {
        isAccessible = true
    }

    @Before
    fun resetStore() {
        fileField.set(TasteSignals, File.createTempFile("taste-signals", ".json").apply {
            deleteOnExit()
        })
        loadedField.setBoolean(TasteSignals, true)
        tracks().clear()
    }

    @Test
    fun `restored likes and dislikes are what the backup exports`() {
        TasteSignals.restoreRatings(
            mapOf("liked" to LikeStatus.LIKE, "disliked" to LikeStatus.DISLIKE),
        )

        assertEquals(
            mapOf("liked" to LikeStatus.LIKE, "disliked" to LikeStatus.DISLIKE),
            TasteSignals.ratings(),
        )
    }

    @Test
    fun `restoring the same rating twice does not add another counter`() {
        TasteSignals.restoreRatings(mapOf("v1" to LikeStatus.LIKE))
        val likes = tracks().getValue("v1").likes.value

        TasteSignals.restoreRatings(mapOf("v1" to LikeStatus.LIKE))

        assertEquals(likes, tracks().getValue("v1").likes.value, 0.0)
    }

    @Suppress("UNCHECKED_CAST")
    private fun tracks(): MutableMap<String, StoredTrack> =
        tracksField.get(TasteSignals) as MutableMap<String, StoredTrack>
}
