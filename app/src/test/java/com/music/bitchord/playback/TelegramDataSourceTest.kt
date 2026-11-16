package com.music.bitchord.playback

import android.net.FakeUri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.telegram.TelegramStreamUri
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Review 11-15 (H draft-7). An open that failed before any byte moved (logged
 * out, message deleted, chat deselected) closed the source, and the close
 * reported a transfer end for a transfer that never started: the listeners
 * were handed no data spec, threw on it, and that exception replaced the
 * IOException the player retries on.
 */
class TelegramDataSourceTest {

    private class Ends : TransferListener {
        val ended = AtomicInteger()

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) = Unit

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            ended.incrementAndGet()
        }
    }

    @Test
    fun `an open that fails stays an IOException and ends no transfer`() {
        val listener = Ends()
        val source = TelegramDataSource()
        source.addTransferListener(listener)
        // No Telegram client in a unit test: the reader cannot resolve the track.
        val spec = DataSpec.Builder().setUri(FakeUri("${TelegramStreamUri.SCHEME}://track/1_2")).build()

        assertThrows(IOException::class.java) { source.open(spec) }

        assertEquals(0, listener.ended.get())
        // The loader closes again after a failed open; that must stay silent too.
        source.close()
        assertEquals(0, listener.ended.get())
    }
}
