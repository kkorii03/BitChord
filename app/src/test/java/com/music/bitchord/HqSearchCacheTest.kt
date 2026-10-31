package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * When a "nothing better" answer may be remembered.
 *
 * A miss is a fact only when every source actually answered. A source that
 * failed, timed out or was skipped for resting produces an empty result too,
 * and caching that as final is what left 'Flowers' on YouTube while Qobuz held
 * the lossless copy (10-31). A request made by hand is the listener saying the
 * cached answer is wrong, so it asks again.
 */
class HqSearchCacheTest {

    @Before
    fun reset() {
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        HqHostTracker.resetForTest()
    }

    private class FakeSource(
        override val configId: String,
        private val sourceKind: SourceKind = SourceKind.ADDON,
        var rows: List<Song> = emptyList(),
        private val streamFormat: StreamFormat? = StreamFormat("flac"),
        private val reportsFailure: Boolean = false,
    ) : MusicSource {
        override val displayName: String get() = configId
        override val kind: SourceKind get() = sourceKind
        var asked = 0
            private set

        override suspend fun health() = SourceHealth.Ok()

        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
        ): List<Song> {
            asked += 1
            if (reportsFailure) {
                // What AddonSource does for a transport fault: log it against
                // the source and hand back an empty list instead of throwing.
                SourceResolver.reportSourceFailure(configId)
            }
            return rows
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? =
            streamFormat?.let { SourceStream(url = "https://$configId/$trackId", format = it) }
    }

    private fun target() = TrackMatcher.Target("Flowers", "Miley Cyrus", durationSec = 200)

    /** A full walk asks each source once per matcher query. */
    private val queries = TrackMatcher.queries(target()).size

    private fun flowers(id: String) = Song(
        videoId = id,
        title = "Flowers",
        artist = "Miley Cyrus",
        thumbnailUrl = null,
        durationText = "3:20",
    )

    @Test
    fun `a none mixed with a failed source is not cached`() = runBlocking {
        val failing = FakeSource("Qobuz", reportsFailure = true)
        val empty = FakeSource("Empty")
        val report = SourceResolver.SearchReport()

        assertNull(
            SourceResolver.bestAcross(
                listOf(failing, empty),
                target(),
                StreamRequest.Best,
                waitForAll = true,
                report = report,
            ),
        )
        assertFalse(
            "a failed source means this was not a confirmed none",
            report.allConclusive,
        )
        // The failing source stops after its first query; the healthy one
        // walks every query.
        assertEquals(1, failing.asked)
        assertEquals(queries, empty.asked)

        assertNull(
            SourceResolver.bestAcross(
                listOf(failing, empty),
                target(),
                StreamRequest.Best,
                waitForAll = true,
            ),
        )
        // The source that failed is asked again; its empty answer was never a
        // confirmed miss. The one that answered normally is not re-asked.
        assertEquals("a failed source's empty answer was cached as final", 2, failing.asked)
        assertEquals(queries, empty.asked)
    }

    @Test
    fun `a conclusive none is cached`() = runBlocking {
        val empty = FakeSource("Empty")
        val report = SourceResolver.SearchReport()

        assertNull(
            SourceResolver.bestAcross(
                listOf(empty),
                target(),
                StreamRequest.Best,
                waitForAll = true,
                report = report,
            ),
        )
        assertTrue(report.allConclusive)

        assertNull(
            SourceResolver.bestAcross(listOf(empty), target(), StreamRequest.Best, waitForAll = true),
        )
        assertEquals("a normal empty answer should be remembered", queries, empty.asked)
    }

    @Test
    fun `a manual request ignores a cached empty answer`() = runBlocking {
        val source = FakeSource("Qobuz")

        assertNull(
            SourceResolver.bestAcross(listOf(source), target(), StreamRequest.Best, waitForAll = true),
        )
        source.rows = listOf(flowers("flowers-1"))

        // The automatic path still sees the memoised empty answer.
        assertNull(
            SourceResolver.bestAcross(listOf(source), target(), StreamRequest.Best, waitForAll = true),
        )
        assertEquals(queries, source.asked)

        val found = SourceResolver.bestAcross(
            listOf(source),
            target(),
            StreamRequest.Best,
            waitForAll = true,
            manual = true,
        )
        assertNotNull("a hand-made request must ask again", found)
        // The first query now finds the row, so the manual walk stops there.
        assertEquals(queries + 1, source.asked)
    }

    @Test
    fun `a manual request asks a resting source once and clears the rest`() = runBlocking {
        val source = FakeSource("Qobuz", rows = listOf(flowers("flowers-1")))
        SourceResolver.SourceCooldown.failed(source.configId)
        SourceResolver.SourceCooldown.failed(source.configId)
        assertTrue(SourceResolver.SourceCooldown.isResting(source.configId))

        assertNull(
            SourceResolver.bestAcross(listOf(source), target(), StreamRequest.Best, waitForAll = true),
        )
        assertEquals("the automatic path keeps the resting rule", 0, source.asked)

        val found = SourceResolver.bestAcross(
            listOf(source),
            target(),
            StreamRequest.Best,
            waitForAll = true,
            manual = true,
        )
        assertNotNull("a hand-made request asks the resting source", found)
        assertEquals(1, source.asked)
        assertFalse(
            "a successful manual answer clears the rest",
            SourceResolver.SourceCooldown.isResting(source.configId),
        )
    }
}
