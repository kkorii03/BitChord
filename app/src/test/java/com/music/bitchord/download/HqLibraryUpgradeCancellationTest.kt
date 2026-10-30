package com.music.bitchord.download

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.HqLibraryAction
import com.music.bitchord.data.sources.HqLibraryTarget
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stopping an automatic replacement is not a failed replacement.
 *
 * The "cancel all" action cancels the download job, whose finally block used to
 * report it as an ordinary failure. That removed the target from the plan and
 * spent one of its two automatic retries, so two cancels could park a song for
 * good even though its verified pair was never tried.
 */
class HqLibraryUpgradeCancellationTest {

    private lateinit var context: Context

    private val planField = HqLibraryUpgrade::class.java
        .getDeclaredField("plan")
        .apply { isAccessible = true }
    private val activeField = HqLibraryUpgrade::class.java
        .getDeclaredField("activeVideoId")
        .apply { isAccessible = true }
    private val doneField = HqLibraryUpgrade::class.java
        .getDeclaredField("done")
        .apply { isAccessible = true }
    private val totalField = HqLibraryUpgrade::class.java
        .getDeclaredField("total")
        .apply { isAccessible = true }
    private val failuresField = HqLibraryUpgrade::class.java
        .getDeclaredField("failures")
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val failures = failuresField.get(HqLibraryUpgrade) as MutableMap<String, Any>

    @Before
    fun setUp() {
        context = FakeContext.create(FakePreferences())
        HqLibraryUpgrade::class.java.getDeclaredMethod(
            "ensureLoaded",
            Context::class.java,
        ).apply { isAccessible = true }.invoke(HqLibraryUpgrade, context)
    }

    @After
    fun tearDown() {
        planField.set(HqLibraryUpgrade, emptyList<Any>())
        activeField.set(HqLibraryUpgrade, null)
        doneField.setInt(HqLibraryUpgrade, 0)
        totalField.setInt(HqLibraryUpgrade, 0)
        failures.clear()
    }

    @Test
    fun `cancelling the active target keeps the plan and its retry budget`() {
        planField.set(HqLibraryUpgrade, listOf(newPendingTarget("video-1")))
        activeField.set(HqLibraryUpgrade, "video-1")
        doneField.setInt(HqLibraryUpgrade, 1)
        totalField.setInt(HqLibraryUpgrade, 3)

        HqLibraryUpgrade.onDownloadSettled(
            context = context,
            videoId = "video-1",
            success = false,
            cancelled = true,
        )

        @Suppress("UNCHECKED_CAST")
        val plan = planField.get(HqLibraryUpgrade) as List<Any>
        assertEquals("the target is still waiting for its replacement", 1, plan.size)
        assertEquals(1, doneField.getInt(HqLibraryUpgrade))
        assertEquals(3, totalField.getInt(HqLibraryUpgrade))
        assertTrue("a cancellation records no failure", failures.isEmpty())
        assertNull(activeField.get(HqLibraryUpgrade))
    }

    @Test
    fun `a late cancellation for another id does not clear this target`() {
        planField.set(HqLibraryUpgrade, listOf(newPendingTarget("video-1")))
        activeField.set(HqLibraryUpgrade, "video-1")

        HqLibraryUpgrade.onDownloadSettled(
            context = context,
            videoId = "video-2",
            success = false,
            cancelled = true,
        )

        assertEquals("video-1", activeField.get(HqLibraryUpgrade))
    }

    @Test
    fun `a parked plan still resumes a worker for its cooldown wake`() {
        assertTrue(HqLibraryUpgrade.shouldResumePlan(planIsNotEmpty = true, hasRunnableTarget = false))
        assertTrue(HqLibraryUpgrade.shouldResumePlan(planIsNotEmpty = true, hasRunnableTarget = true))
        assertTrue(HqLibraryUpgrade.shouldResumePlan(planIsNotEmpty = false, hasRunnableTarget = true))
        assertFalse(HqLibraryUpgrade.shouldResumePlan(planIsNotEmpty = false, hasRunnableTarget = false))
    }

    private fun newPendingTarget(videoId: String): Any {
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
}
