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
 * A persisted plan survives a restart while one of its targets is still
 * inside the automatic retry cooldown. [HqLibraryUpgrade.prunePlanLocked]
 * keeps that target on purpose, so [HqLibraryUpgrade.runOne] must not then
 * pick it as the next download and ask the server again immediately.
 */
class HqLibraryUpgradeSelectionTest {

    private val foregroundField = AppForeground::class.java
        .getDeclaredField("isForeground")
        .apply { isAccessible = true }
    private var originalForeground = false

    private val planField = HqLibraryUpgrade::class.java
        .getDeclaredField("plan")
        .apply { isAccessible = true }
    private val failuresField = HqLibraryUpgrade::class.java
        .getDeclaredField("failures")
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val failures = failuresField.get(HqLibraryUpgrade) as MutableMap<String, Any>

    @Before
    fun setUp() {
        originalForeground = AppForeground.isForeground
        foregroundField.setBoolean(AppForeground, true)
    }

    @After
    fun tearDown() {
        foregroundField.setBoolean(AppForeground, originalForeground)
        planField.set(HqLibraryUpgrade, emptyList<Any>())
        failures.clear()
    }

    @Test
    fun `a target resting inside the retry cooldown is not selected`() {
        planField.set(
            HqLibraryUpgrade,
            listOf(
                newPendingTarget("video-parked"),
                newPendingTarget("video-ready"),
            ),
        )
        failures["video-parked\u0002source\u0002track"] = newFailure(
            attempts = 1,
            lastFailureMs = 1_000_000L,
        )

        val selected = nextRunnable(nowMs = 1_001_000L)

        assertEquals("video-ready", videoIdOf(selected))
    }

    @Test
    fun `a target past its retry cooldown is selected`() {
        planField.set(HqLibraryUpgrade, listOf(newPendingTarget("video-1")))
        failures["video-1\u0002source\u0002track"] = newFailure(
            attempts = 1,
            lastFailureMs = 1_000_000L,
        )

        val selected = nextRunnable(
            nowMs = 1_000_000L + com.music.bitchord.data.net.AutomaticRetryPolicy.COOLDOWN_MS,
        )

        assertEquals("video-1", videoIdOf(selected))
    }

    @Test
    fun `a fully parked plan waits until the cooldown ends`() {
        planField.set(HqLibraryUpgrade, listOf(newPendingTarget("video-1")))
        failures["video-1\u0002source\u0002track"] = newFailure(
            attempts = 1,
            lastFailureMs = 1_000_000L,
        )

        val wait = nextParkedWait(nowMs = 1_001_000L)

        assertEquals(
            com.music.bitchord.data.net.AutomaticRetryPolicy.COOLDOWN_MS - 1_000L,
            wait,
        )
    }

    @Test
    fun `a rebuilt plan keeps parked entries so their cooldown wake survives`() {
        val built = listOf(newPendingTarget("video-new"))
        val kept = listOf(newPendingTarget("video-parked"), newPendingTarget("video-new"))

        val merged = mergePlan(built, kept, resumable = kept)

        // The newly eligible target is first (it is the one the worker runs),
        // the parked one is kept so its cooldown deadline is not lost, and the
        // rebuilt entry replaces the stale copy for the same song.
        assertEquals(listOf("video-new", "video-parked"), merged.map(::videoIdOf))
    }

    @Test
    fun `a kept entry the policy no longer selects is dropped`() {
        val built = listOf(newPendingTarget("video-new"))
        val kept = listOf(newPendingTarget("video-parked"), newPendingTarget("video-new"))
        // Only the newly built target is still selected by the current
        // settings/policy; the parked one was omitted for a reason other than
        // its cooldown and must not be resurrected.
        val merged = mergePlan(built, kept, resumable = built)

        assertEquals(listOf("video-new"), merged.map(::videoIdOf))
    }

    private fun nextRunnable(nowMs: Long): Any? {
        val method = HqLibraryUpgrade::class.java.getDeclaredMethod(
            "nextRunnableTargetLocked",
            Long::class.javaPrimitiveType,
            Map::class.java,
        ).apply { isAccessible = true }
        return method.invoke(HqLibraryUpgrade, nowMs, emptyMap<String, SavedSongMetadata>())
    }

    private fun nextParkedWait(nowMs: Long): Long? {
        val method = HqLibraryUpgrade::class.java.getDeclaredMethod(
            "nextParkedWaitLocked",
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return method.invoke(HqLibraryUpgrade, nowMs) as Long?
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergePlan(built: List<Any>, kept: List<Any>, resumable: List<Any>): List<Any> {
        val method = HqLibraryUpgrade::class.java.getDeclaredMethod(
            "mergeParkedPlan",
            List::class.java,
            List::class.java,
            List::class.java,
        ).apply { isAccessible = true }
        return method.invoke(HqLibraryUpgrade, built, kept, resumable) as List<Any>
    }

    private fun videoIdOf(pending: Any?): String? {
        if (pending == null) return null
        val target = pending.javaClass.getDeclaredField("target")
            .apply { isAccessible = true }
            .get(pending) as HqLibraryTarget
        return target.videoId
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
