package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.HqLibraryAction
import com.music.bitchord.data.sources.HqLibraryTarget
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.startup.AppForeground
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Backgrounding the app must not empty the persisted high-quality plan.
 *
 * [HqLibraryUpgrade.onBackground] cancels the schedule on the promise that the
 * plan stays on disk. [HqLibraryUpgrade.hasRetryableWork] can run after the
 * foreground check in [HqLibraryUpgrade.scheduleIfEnabled] has already passed,
 * so pruning must not treat "no window right now" as permanent ineligibility.
 */
class HqLibraryUpgradeBackgroundPruneTest {

    private val foregroundField = AppForeground::class.java
        .getDeclaredField("isForeground")
        .apply { isAccessible = true }
    private var originalForeground = false

    private val planField = HqLibraryUpgrade::class.java
        .getDeclaredField("plan")
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
        originalForeground = AppForeground.isForeground
    }

    @After
    fun tearDown() {
        foregroundField.setBoolean(AppForeground, originalForeground)
        planField.set(HqLibraryUpgrade, emptyList<Any>())
        doneField.setInt(HqLibraryUpgrade, 0)
        totalField.setInt(HqLibraryUpgrade, 0)
        failures.clear()
    }

    @Test
    fun `a background event does not prune the plan`() {
        foregroundField.setBoolean(AppForeground, false)
        planField.set(HqLibraryUpgrade, listOf(newPendingTarget("video-1")))
        doneField.setInt(HqLibraryUpgrade, 3)
        totalField.setInt(HqLibraryUpgrade, 5)

        val prune = HqLibraryUpgrade::class.java.getDeclaredMethod(
            "prunePlanLocked",
            Map::class.java,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        prune.invoke(HqLibraryUpgrade, emptyMap<String, SavedSongMetadata>(), 1_000L)

        @Suppress("UNCHECKED_CAST")
        val plan = planField.get(HqLibraryUpgrade) as List<Any>
        assertEquals(1, plan.size)
        assertEquals(3, doneField.getInt(HqLibraryUpgrade))
        assertEquals(5, totalField.getInt(HqLibraryUpgrade))
    }

    @Test
    fun `a target resting inside the retry cooldown is parked, not pruned`() {
        foregroundField.setBoolean(AppForeground, false)
        planField.set(HqLibraryUpgrade, listOf(newPendingTarget("video-1")))
        // One failed attempt, minutes ago: the automatic policy's 24h cooldown
        // is still running, so this is temporarily parked rather than
        // permanently ineligible. A background event must keep it on disk.
        failures["video-1\u0002source\u0002track"] = newFailure(attempts = 1, lastFailureMs = 999_000L)

        prune(nowMs = 1_000_000L)

        @Suppress("UNCHECKED_CAST")
        val plan = planField.get(HqLibraryUpgrade) as List<Any>
        assertEquals(1, plan.size)
    }

    @Test
    fun `a target with exhausted automatic retries is pruned`() {
        foregroundField.setBoolean(AppForeground, false)
        planField.set(HqLibraryUpgrade, listOf(newPendingTarget("video-1")))
        failures["video-1\u0002source\u0002track"] = newFailure(
            attempts = com.music.bitchord.data.net.AutomaticRetryPolicy.MAX_FAILURES,
            lastFailureMs = 999_000L,
        )

        prune(nowMs = 1_000_000L)

        @Suppress("UNCHECKED_CAST")
        val plan = planField.get(HqLibraryUpgrade) as List<Any>
        assertEquals(0, plan.size)
    }

    private fun prune(nowMs: Long) {
        val prune = HqLibraryUpgrade::class.java.getDeclaredMethod(
            "prunePlanLocked",
            Map::class.java,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        prune.invoke(HqLibraryUpgrade, emptyMap<String, SavedSongMetadata>(), nowMs)
    }

    private fun newFailure(attempts: Int, lastFailureMs: Long): Any {
        val failureClass = Class.forName(
            "com.music.bitchord.download.HqLibraryUpgrade\$Failure",
        )
        val constructor = failureClass.declaredConstructors
            .first { it.parameterCount == 2 }
            .apply { isAccessible = true }
        return constructor.newInstance(attempts, lastFailureMs)
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
