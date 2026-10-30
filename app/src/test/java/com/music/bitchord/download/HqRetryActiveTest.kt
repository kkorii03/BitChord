package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.HqLibraryAction
import com.music.bitchord.data.sources.HqLibraryTarget
import com.music.bitchord.data.sources.HqTier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A successful download that did not carry the pass's verified pair is not a
 * failed replacement. Releasing the active target must leave it in the plan
 * and leave the failure budget untouched; a regression to
 * `settle(success = false)` would otherwise delete the target and spend its
 * retry without any helper test noticing.
 */
class HqRetryActiveTest {

    private val planField = field("plan")
    private val activeField = field("activeVideoId")
    private val failuresField = field("failures")

    @Before
    fun setUp() {
        clear()
    }

    @After
    fun tearDown() {
        clear()
    }

    private fun clear() {
        planField.set(HqLibraryUpgrade, emptyList<Any>())
        activeField.set(HqLibraryUpgrade, null)
        (failuresField.get(HqLibraryUpgrade) as MutableMap<*, *>).clear()
    }

    @Test
    fun `releasing an unpaired download keeps the target and spends no retry`() {
        val pending = pendingTarget("video-1")
        planField.set(HqLibraryUpgrade, listOf(pending))
        activeField.set(HqLibraryUpgrade, "video-1")

        val stillPlanned = HqLibraryUpgrade.releaseActiveKeepingPlan("video-1")

        assertTrue(stillPlanned)
        assertEquals(listOf(pending), planField.get(HqLibraryUpgrade))
        assertNull(activeField.get(HqLibraryUpgrade))
        assertTrue((failuresField.get(HqLibraryUpgrade) as Map<*, *>).isEmpty())
    }

    @Test
    fun `releasing a target that is not active changes nothing`() {
        val pending = pendingTarget("video-1")
        planField.set(HqLibraryUpgrade, listOf(pending))
        activeField.set(HqLibraryUpgrade, "video-1")

        val stillPlanned = HqLibraryUpgrade.releaseActiveKeepingPlan("other")

        assertTrue(stillPlanned.not())
        assertEquals("video-1", activeField.get(HqLibraryUpgrade))
    }

    private fun pendingTarget(videoId: String): Any {
        val target = HqLibraryTarget(
            videoId = videoId,
            action = HqLibraryAction.REPLACE,
            verifiedTier = HqTier.LOSSLESS_16,
            sourceConfigId = "source",
            trackId = "track",
        )
        val song = Song(
            videoId = videoId,
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )
        val pendingClass = Class.forName(
            "com.music.bitchord.download.HqLibraryUpgrade\$PendingTarget",
        )
        val constructor = pendingClass.declaredConstructors
            .first { it.parameterCount == 4 }
            .apply { isAccessible = true }
        return constructor.newInstance(target, song, 0, 0L)
    }

    private fun field(name: String) =
        HqLibraryUpgrade::class.java.getDeclaredField(name).apply { isAccessible = true }
}
