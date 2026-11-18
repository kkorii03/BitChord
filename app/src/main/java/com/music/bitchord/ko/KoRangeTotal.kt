package com.music.bitchord.ko

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * [KO] AUD-LEN: the whole resource's size that a 206 answer to a bounded warm
 * names in `Content-Range: bytes x-y/total`.
 *
 * Media3's cache records a length only for unbounded reads, so the audition's
 * 1 MiB head warm left the entry without one. The audition then opened with an
 * unknown length, and a FLAC without a SEEKTABLE got `SeekMap.Unseekable`:
 * 11-18 16:00 WKZO-CWeOVA "replacement cannot seek to 44594ms ... seekable=false
 * read=1048576B" although the verify had read all 28,883,400 bytes. With the
 * length known the extractor uses its binary-search seeker. Only a server's own
 * 206 total is taken, never a guess: a wrong total sends that seeker past EOF.
 */
internal object KoRangeTotal {

    private val RANGE = Regex("""^\s*bytes\s+\d+-\d+/(\d+)\s*$""", RegexOption.IGNORE_CASE)

    /** The total in a `Content-Range` value, or null for none, `*` or garbage. */
    fun parse(contentRange: String?): Long? =
        contentRange?.let { RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }?.takeIf { it > 0 }

    /** The total in [headers]' `Content-Range`, whatever case the server used. */
    fun of(headers: Map<String, List<String>>): Long? =
        headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstNotNullOfOrNull(::parse)

    /** Wraps [upstream] so each open reports the 206 total to [noted]; reads are untouched. */
    fun noting(upstream: DataSource.Factory, noted: (Long) -> Unit): DataSource.Factory =
        DataSource.Factory { Noting(upstream.createDataSource(), noted) }

    private class Noting(private val inner: DataSource, private val noted: (Long) -> Unit) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) =
            inner.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val length = inner.open(dataSpec)
            runCatching { of(inner.responseHeaders) }.getOrNull()?.let(noted)
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)

        override fun getUri(): Uri? = inner.uri

        override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

        override fun close() = inner.close()
    }
}
