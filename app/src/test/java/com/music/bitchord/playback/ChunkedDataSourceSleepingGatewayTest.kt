package com.music.bitchord.playback

import android.net.FakeUri
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * A free-tier addon stream that answers 502/503/504 is asleep. Playback must
 * send the one wake request, rest the host, and leave recovery to the caller
 * instead of retrying the cold container three times on the loader thread.
 *
 * A module stream that answers 403/404/410 is not the same evidence. The URL is
 * dead and must never be opened twice, but one missing file or one expired
 * signature does not say the server is down — so the host rests only after it
 * has refused two different streams, and a healthy second URL on that host is
 * still served.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSourceSleepingGatewayTest {

    @Before
    fun setUp() {
        HqHostTracker.resetForTest()
        StreamRefusalRecovery.resetForTest()
    }

    @After
    fun tearDown() {
        HqHostTracker.resetForTest()
        StreamRefusalRecovery.resetForTest()
    }

    @Test
    fun `a sleeping stream gateway is opened once and rested`() {
        for (code in listOf(502, 503, 504)) {
            HqHostTracker.resetForTest()
            val upstream = RefusingDataSource(code)
            val dataSource = ChunkedDataSource(upstream, chunkBytes = 1_024L)
            val spec = DataSpec.Builder()
                .setUri(FakeUri("https://tracks.example/audio?clen=4096"))
                .setPosition(0L)
                .setLength(1_024L)
                .build()

            assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
                dataSource.open(spec)
            }

            assertEquals("HTTP $code must be asked exactly once", 1, upstream.opens)
            assertTrue(
                "HTTP $code must rest the host after the single wake request",
                HqHostTracker.isResting("tracks.example"),
            )
        }
    }

    @Test
    fun `one refused module stream does not rest a shared host`() {
        for (code in listOf(403, 404, 410)) {
            HqHostTracker.resetForTest()
            StreamRefusalRecovery.resetForTest()
            val upstream = RefusingDataSource(code)
            val dataSource = ChunkedDataSource(upstream, chunkBytes = 1_024L)

            assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
                dataSource.open(moduleSpec("https://tracks.example/one"))
            }

            assertEquals("HTTP $code must be asked exactly once", 1, upstream.opens)
            assertFalse(
                "HTTP $code on one file is not evidence the host is down",
                HqHostTracker.isResting("tracks.example"),
            )
        }
    }

    @Test
    fun `a healthy second stream on the same host is still served after one 404`() {
        HqHostTracker.resetForTest()
        StreamRefusalRecovery.resetForTest()
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            ChunkedDataSource(RefusingDataSource(404), chunkBytes = 1_024L)
                .open(moduleSpec("https://tracks.example/gone"))
        }

        val healthy = ServingDataSource()
        ChunkedDataSource(healthy, chunkBytes = 1_024L)
            .open(moduleSpec("https://tracks.example/fine"))

        assertEquals("the healthy URL must reach the server", 1, healthy.opens)
    }

    @Test
    fun `a stream that was refused is never opened twice`() {
        HqHostTracker.resetForTest()
        StreamRefusalRecovery.resetForTest()
        val upstream = RefusingDataSource(403)
        val dataSource = ChunkedDataSource(upstream, chunkBytes = 1_024L)

        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            dataSource.open(moduleSpec("https://tracks.example/one"))
        }
        // The second open answers from the recorded refusal, so the player's
        // recovery can fall back to YouTube without another round trip.
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            dataSource.open(moduleSpec("https://tracks.example/one"))
        }

        assertEquals("a refused URL must not be asked again", 1, upstream.opens)
    }

    @Test
    fun `a host that refuses two different streams is rested`() {
        HqHostTracker.resetForTest()
        StreamRefusalRecovery.resetForTest()
        val upstream = RefusingDataSource(403)

        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            ChunkedDataSource(upstream, chunkBytes = 1_024L)
                .open(moduleSpec("https://tracks.example/one"))
        }
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
            ChunkedDataSource(upstream, chunkBytes = 1_024L)
                .open(moduleSpec("https://tracks.example/two"))
        }

        assertTrue(
            "two dead streams from one host must rest it for the next track",
            HqHostTracker.isResting("tracks.example"),
        )
    }

    /** No `clen`, so this takes the pass-through branch every module stream comes through. */
    private fun moduleSpec(url: String): DataSpec =
        DataSpec.Builder().setUri(FakeUri(url)).build()

    private class ServingDataSource : DataSource {
        var opens = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            opens += 1
            return 0L
        }

        override fun getUri(): Uri? = null

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

        override fun close() = Unit
    }

    private class RefusingDataSource(private val code: Int) : DataSource {
        var opens = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            opens += 1
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                "sleeping gateway",
                IOException("HTTP $code"),
                emptyMap(),
                dataSpec,
                ByteArray(0),
            )
        }

        override fun getUri(): Uri? = null

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

        override fun close() = Unit
    }
}
