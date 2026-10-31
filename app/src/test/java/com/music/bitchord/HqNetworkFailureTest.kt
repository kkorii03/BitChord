package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.HqHostTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A transport fault is not a catalogue miss.
 *
 * Network exceptions used to bypass the failure mark while HTTP errors raised
 * it, so an empty answer produced after a socket timeout, a DNS failure or a
 * refused connection could be cached as a confirmed "nobody has it". They must
 * raise the mark (never cached), must not treat cancellation as a failure, and
 * must only count toward the source's rest while the device is online.
 */
class HqNetworkFailureTest {

    @Before
    fun reset() {
        SourceResolver.clearSearchAnswers()
        SourceResolver.SourceCooldown.resetForTest()
        SourceResolver.offlineCheck = { false }
        HqHostTracker.resetForTest()
    }

    @After
    fun restore() {
        SourceResolver.offlineCheck = { AudioCache.isOffline() }
    }

    private class ThrowingSource(
        override val configId: String,
        private val failure: () -> Exception,
    ) : MusicSource {
        override val displayName: String get() = configId
        override val kind: SourceKind get() = SourceKind.ADDON
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
            throw failure()
        }

        override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? = null
    }

    private fun target() = TrackMatcher.Target("Flowers", "Miley Cyrus", durationSec = 200)

    @Test
    fun `a none mixed with a network exception is not cached`() = runBlocking {
        val source = ThrowingSource("Qobuz") { SocketTimeoutException("read timed out") }
        val report = SourceResolver.SearchReport()

        assertNull(
            SourceResolver.bestAcross(
                listOf(source),
                target(),
                StreamRequest.Best,
                waitForAll = true,
                report = report,
            ),
        )
        assertFalse(
            "a network exception means this was not a confirmed none",
            report.allConclusive,
        )
        assertEquals("error", report.outcomes().single().verdict)

        assertNull(
            SourceResolver.bestAcross(listOf(source), target(), StreamRequest.Best, waitForAll = true),
        )
        assertEquals("a network failure's empty answer was cached as final", 2, source.asked)
    }

    @Test
    fun `cancellation is not counted as a failure`() {
        val source = ThrowingSource("Qobuz") { CancellationException("caller gave up") }

        val thrown = runCatching {
            runBlocking {
                SourceResolver.bestAcross(
                    listOf(source),
                    target(),
                    StreamRequest.Best,
                    waitForAll = true,
                )
            }
        }.exceptionOrNull()

        assertTrue("cancellation must be re-thrown", thrown is CancellationException)
        assertEquals(0L, SourceResolver.SourceCooldown.failureMark(source.configId))
        assertFalse(SourceResolver.SourceCooldown.isResting(source.configId))
    }

    @Test
    fun `offline network failures do not rest the source`() = runBlocking {
        SourceResolver.offlineCheck = { true }
        val source = ThrowingSource("Qobuz") { UnknownHostException("no dns") }

        repeat(2) {
            assertNull(
                SourceResolver.bestAcross(listOf(source), target(), StreamRequest.Best, waitForAll = true),
            )
        }

        assertEquals("each walk still asks the source", 2, source.asked)
        assertEquals(2L, SourceResolver.SourceCooldown.failureMark(source.configId))
        assertFalse(
            "an offline device must not park every source for 30 minutes",
            SourceResolver.SourceCooldown.isResting(source.configId),
        )
    }

    @Test
    fun `online network failures count toward the rest rule`() = runBlocking {
        val source = ThrowingSource("Qobuz") { ConnectException("connection refused") }

        repeat(2) {
            assertNull(
                SourceResolver.bestAcross(listOf(source), target(), StreamRequest.Best, waitForAll = true),
            )
        }

        assertEquals(2L, SourceResolver.SourceCooldown.failureMark(source.configId))
        assertTrue(
            "a source-specific network fault still follows the failure count",
            SourceResolver.SourceCooldown.isResting(source.configId),
        )
    }
}
