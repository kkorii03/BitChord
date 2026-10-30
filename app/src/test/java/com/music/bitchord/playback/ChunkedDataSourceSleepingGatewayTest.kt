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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * A free-tier addon stream that answers 502/503/504 is asleep. Playback must
 * send the one wake request, rest the host, and leave recovery to the caller
 * instead of retrying the cold container three times on the loader thread.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSourceSleepingGatewayTest {

    @Before
    fun setUp() {
        HqHostTracker.resetForTest()
    }

    @After
    fun tearDown() {
        HqHostTracker.resetForTest()
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
