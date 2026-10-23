package com.music.bitchord.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.music.bitchord.data.Http
import com.music.bitchord.data.net.ProxyPlayback

/**
 * Picks the HTTP client by where a stream URL was minted.
 *
 * A URL that came from a player request sent through the proxy is tied to that
 * address, so its bytes have to leave the same way. Everything else keeps the
 * app's shared client. The decision is the explicit [ProxyPlayback] record,
 * never a guess from the host name, and it is made per [open] because the
 * chunked reader above reopens the same source for every range.
 */
@UnstableApi
class ProxyAwareDataSourceFactory(
    private val direct: DataSource.Factory = OkHttpDataSource.Factory(Http.client),
    private val proxied: DataSource.Factory = OkHttpDataSource.Factory(Http.proxyMediaClient),
) : DataSource.Factory {

    override fun createDataSource(): DataSource = object : DataSource {
        private var active: DataSource? = null
        private val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            active?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val factory = if (ProxyPlayback.usesProxyFor(dataSpec.uri.toString())) proxied else direct
            val source = factory.createDataSource()
            listeners.forEach(source::addTransferListener)
            active = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(active) { "read before open" }.read(buffer, offset, length)

        override fun getUri(): android.net.Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            active?.responseHeaders ?: emptyMap()

        override fun close() {
            active?.close()
            active = null
        }
    }
}
