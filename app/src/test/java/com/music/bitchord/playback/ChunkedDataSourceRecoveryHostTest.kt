package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.ko.KoChunkedHostCost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A mid-play recovery replaces a refused URL. The rest of the transfer is
 * served by the replacement, so [ChunkedDataSource.close] must credit that
 * host rather than the refused original.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSourceRecoveryHostTest {

    private val hostCostField = ChunkedDataSource::class.java
        .getDeclaredField("koHostCost")
        .apply { isAccessible = true }
    private val currentHostField = KoChunkedHostCost::class.java
        .getDeclaredField("currentHost")
        .apply { isAccessible = true }
    private val openedAtField = KoChunkedHostCost::class.java
        .getDeclaredField("openedAtMs")
        .apply { isAccessible = true }
    private val bytesReadField = KoChunkedHostCost::class.java
        .getDeclaredField("bytesRead")
        .apply { isAccessible = true }

    private val adopt = KoChunkedHostCost::class.java
        .getDeclaredMethod("adoptRecoveredHost", String::class.java)
        .apply { isAccessible = true }

    @Before
    fun setUp() {
        HqHostTracker.resetForTest()
    }

    @After
    fun tearDown() {
        HqHostTracker.resetForTest()
    }

    @Test
    fun `a recovered replacement becomes the host credited at close`() {
        val dataSource = ChunkedDataSource(NoopDataSource(), chunkBytes = 1_024L)
        val hostCost = hostCostField.get(dataSource)
        currentHostField.set(hostCost, "refused.example")
        bytesReadField.set(hostCost, 4_096L)
        openedAtField.set(hostCost, 1L)

        adopt.invoke(hostCost, "https://replacement.example/audio?clen=4096")

        assertEquals("replacement.example", currentHostField.get(hostCost))
        assertEquals("bytes read before the refusal belong to the refused host", 0L, bytesReadField.get(hostCost))
        assertEquals(true, (openedAtField.get(hostCost) as Long) > 1L)
    }

    private class NoopDataSource : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = 0L
        override fun getUri(): Uri? = null
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun close() = Unit
    }
}
