package com.music.bitchord.download

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deleting a whole release must keep a file another release also lists, and
 * must keep the record when a file cannot be removed so the caller can say so.
 */
class DownloadsDeleteCollectionTest {

    private val previousSaved = Downloads.savedValue()
    private val previousCollections = Downloads.collections.value

    @Before
    fun setUp() {
        Downloads.setSaved(emptyMap())
        Downloads.setCollections(emptyMap())
    }

    @After
    fun tearDown() {
        Downloads.setSaved(previousSaved)
        Downloads.setCollections(previousCollections)
    }

    @Test
    fun `a file another release lists is kept`() = runTest {
        Downloads.setSaved(mapOf("own" to "file:/own", "shared" to "file:/shared"))
        Downloads.setCollections(
            mapOf(
                "release" to SavedCollection("release", "Release", videoIds = listOf("own", "shared")),
                "other" to SavedCollection("other", "Other", videoIds = listOf("shared")),
            ),
        )
        val deleted = mutableListOf<String>()

        assertTrue(
            Downloads.deleteCollection("release") { videoId ->
                deleted += videoId
                Downloads.setSaved(Downloads.savedValue() - videoId)
                true
            },
        )

        assertEquals(listOf("own"), deleted)
        assertEquals(mapOf("shared" to "file:/shared"), Downloads.savedValue())
        assertNull(Downloads.collections.value["release"])
        assertTrue(Downloads.collections.value.containsKey("other"))
    }

    @Test
    fun `a file that cannot be deleted keeps the record and reports failure`() = runTest {
        Downloads.setSaved(mapOf("blocked" to "file:/blocked"))
        Downloads.setCollections(
            mapOf("release" to SavedCollection("release", "Release", videoIds = listOf("blocked"))),
        )

        assertFalse(Downloads.deleteCollection("release") { false })

        assertTrue(Downloads.savedValue().containsKey("blocked"))
        assertTrue(Downloads.collections.value.containsKey("release"))
    }
}
