package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.telegram.TelegramAudioReader
import com.music.bitchord.telegram.TelegramClientManager
import com.music.bitchord.telegram.TelegramStreamUri
import java.io.IOException

/**
 * Reads a Telegram stream for ExoPlayer.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later)
 * TelegramDataSource, wired into this app's DataSource chain. TDLib downloads
 * only the ranges the player asks for; the reader keeps the client open while
 * the stream is live and releases it on close.
 */
class TelegramDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var reader: TelegramAudioReader? = null
    private var openedUri: Uri? = null
    private var trackId: String? = null
    private var streamStarted = false

    override fun open(dataSpec: DataSpec): Long {
        if (reader != null) throw IOException("Telegram data source is already open")
        val id = TelegramStreamUri.trackId(dataSpec.uri)
            ?: throw IOException("Not a Telegram track: ${dataSpec.uri}")
        transferInitializing(dataSpec)
        trackId = id
        TelegramClientManager.beginStream(id)
        streamStarted = true
        try {
            val opened = TelegramAudioReader.open(id, dataSpec.position, dataSpec.length)
            reader = opened
            openedUri = dataSpec.uri
            transferStarted(dataSpec)
            return opened.remaining()
        } catch (failure: Throwable) {
            close()
            throw if (failure is IOException) failure
            else IOException("Telegram audio unavailable: ${failure.message}", failure)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val current = reader ?: throw IOException("Telegram data source is not open")
        val read = current.read(buffer, offset, length)
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = openedUri

    override fun getResponseHeaders(): Map<String, List<String>> {
        val current = reader ?: return emptyMap()
        return mapOf(
            "Content-Length" to listOf(current.total.toString()),
            "Content-Type" to listOf(current.mimeType),
        )
    }

    override fun close() {
        val id = trackId
        try {
            reader?.close()
        } finally {
            reader = null
            openedUri = null
            trackId = null
            if (streamStarted) {
                streamStarted = false
                id?.let(TelegramClientManager::endStream)
            }
            transferEnded()
        }
    }

    /** Routes Telegram's internal scheme to [TelegramDataSource] and everything else below. */
    class RoutingFactory(private val downstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(downstream.createDataSource())

        private class RoutingDataSource(private val inner: DataSource) : DataSource {
            private val telegram = TelegramDataSource()
            private var active: DataSource = inner

            override fun addTransferListener(transferListener: TransferListener) {
                inner.addTransferListener(transferListener)
                telegram.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                active = if (dataSpec.uri.scheme == TelegramStreamUri.SCHEME) telegram else inner
                return active.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                active.read(buffer, offset, length)

            override fun getUri(): Uri? = active.uri

            override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

            override fun close() = active.close()
        }
    }
}
