package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.net.SharedLookups
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home used to await its feed browse and only then start the chart lookup that
 * replaces the Trending shelf. The chart is independent, so the cold start paid
 * their sum. These tests hold the feed open until the chart has started: a
 * sequential implementation deadlocks and fails the timeout.
 */
class HomeChartOverlapTest {

    private fun shelf(title: String) = HomeShelf(title = title, items = emptyList())

    @Test
    fun `chart lookup starts while the home feed is still loading`() = runBlocking {
        val lookups = SharedLookups<HomeShelf?>(CoroutineScope(Dispatchers.IO))
        val chartStarted = CompletableDeferred<Unit>()
        val feedMayFinish = CompletableDeferred<Unit>()
        val chart = shelf("인기곡")

        val loaded = withTimeout(2_000) {
            YtMusicRepository.loadWithChartBefore(
                startChart = {
                    lookups.lookup("KR") {
                        chartStarted.complete(Unit)
                        feedMayFinish.complete(Unit)
                        chart
                    }
                },
                load = {
                    feedMayFinish.await()
                    "feed"
                },
            )
        }

        assertEquals("feed", loaded.first)
        assertEquals(chart, loaded.second)
        assertTrue(chartStarted.isCompleted, "the chart request began before the feed answered")
    }

    @Test
    fun `an answer that cannot use the chart cancels it`() = runBlocking {
        val lookups = SharedLookups<HomeShelf?>(CoroutineScope(Dispatchers.IO))
        val chartStarted = CompletableDeferred<Unit>()
        val chartMayFinish = CompletableDeferred<Unit>()
        val chart = shelf("인기곡")

        val loaded = YtMusicRepository.loadWithChartBefore(
            startChart = {
                lookups.lookup("KR") {
                    chartStarted.complete(Unit)
                    chartMayFinish.await()
                    chart
                }
            },
            load = {
                chartStarted.await()
                "fallback"
            },
            keepChart = { false },
        )

        assertEquals("fallback", loaded.first)
        assertNull(loaded.second)
        withTimeout(2_000) {
            while (lookups.isRunning("KR")) kotlinx.coroutines.yield()
        }
        assertFalse(lookups.isRunning("KR"), "the unused chart lookup was cancelled")
    }

    @Test
    fun `a fallback does not cancel a chart another caller started`() = runBlocking {
        val lookups = SharedLookups<HomeShelf?>(CoroutineScope(Dispatchers.IO))
        val release = CompletableDeferred<Unit>()
        val chart = shelf("인기곡")
        val owner = lookups.lookup("KR") {
            release.await()
            chart
        }
        val joined = lookups.lookup("KR") { error("a joined lookup must not start a second chart") }
        assertTrue(joined.joined)

        val loaded = YtMusicRepository.loadWithChartBefore(
            startChart = { joined },
            load = { "fallback" },
            keepChart = { false },
        )

        assertEquals("fallback", loaded.first)
        assertNull(loaded.second)
        assertTrue(lookups.isRunning("KR"), "the owning caller's chart keeps running")
        release.complete(Unit)
        assertEquals(chart, owner.result.await())
    }

    @Test
    fun `a failed load does not cancel a chart another caller started`() = runBlocking {
        val lookups = SharedLookups<HomeShelf?>(CoroutineScope(Dispatchers.IO))
        val release = CompletableDeferred<Unit>()
        val chart = shelf("인기곡")
        val owner = lookups.lookup("KR") {
            release.await()
            chart
        }
        val joined = lookups.lookup("KR") { error("a joined lookup must not start a second chart") }
        assertTrue(joined.joined)

        try {
            YtMusicRepository.loadWithChartBefore(
                startChart = { joined },
                load = { throw IllegalStateException("browse failed") },
            )
        } catch (expected: IllegalStateException) {
            assertEquals("browse failed", expected.message)
        }

        assertTrue(lookups.isRunning("KR"), "the owning caller's chart keeps running")
        release.complete(Unit)
        assertEquals(chart, owner.result.await())
    }
}
