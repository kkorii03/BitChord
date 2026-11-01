package com.music.bitchord.ko

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * [KO] reader-lock
 *
 * The playback data source wrapper that remembers which cache entry the
 * player (including its playlist read-ahead) currently owns. Kept outside the
 * upstream AudioCache file; every decision still goes through the same cache
 * and transfer listeners.
 */
class KoCachingDataSource(
    private val cacheDs: DataSource,
    private val upstreamDs: DataSource,
    private val cacheKeyOf: (Uri) -> String,
    private val notePlayedBytes: (String, Int) -> Unit,
    private val tracker: KoPlaybackReadTracker,
    private val onReaderReleased: (String) -> Unit,
) : DataSource {
    private var activeDs: DataSource = cacheDs
    private var heldKey: String? = null
    private var heldVideoId: String? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cacheDs.addTransferListener(transferListener)
        upstreamDs.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // A DataSource instance is normally closed before it is opened
        // again, but the hold must not be counted twice if it is not.
        // This is not a terminal release: the same source is about to
        // re-retain the key, so it must not wake a head retry that would
        // race the reopened playback reader. A seek/reopen must not
        // count as the reader letting go either.
        releaseHeld(retryAnalysisHead = false)
        val scheme = dataSpec.uri.scheme
        activeDs = if (scheme == "file" || scheme == "content") {
            upstreamDs
        } else {
            cacheDs
        }
        if (scheme != "file" && scheme != "content") {
            // Remember that the player — including the item it buffers
            // ahead in its playlist — owns this cache entry. Read-ahead
            // and the analysis head ask before touching it; Media3
            // gives an entry to one writer, so anything else spends the
            // listener's data writing nowhere.
            val key = cacheKeyOf(dataSpec.uri)
            val videoId = dataSpec.uri.getQueryParameter("v")
            heldKey = key
            heldVideoId = videoId
            tracker.retainKey(key)
            videoId?.let(tracker::retainVideo)
        }
        return try {
            activeDs.open(dataSpec)
        } catch (failure: Throwable) {
            releaseHeld()
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = activeDs.read(buffer, offset, length)
        heldVideoId?.let { if (read > 0) notePlayedBytes(it, read) }
        return read
    }

    override fun getUri(): Uri? = activeDs.uri

    override fun close() {
        try {
            activeDs.close()
        } finally {
            releaseHeld()
        }
    }

    private fun releaseHeld(retryAnalysisHead: Boolean = true) {
        heldKey?.let { key ->
            tracker.releaseKey(key)
            tracker.readerReleased(key)
        }
        heldVideoId?.let { videoId ->
            tracker.releaseVideo(videoId)
            // The held analysis claim waits for this event rather than
            // for the next idle tick to discover the lock is gone. Only
            // a terminal release counts: open() releases before it
            // re-retains the same key, and retrying there would race
            // the reader it just reopened (seek/reopen).
            if (retryAnalysisHead) onReaderReleased(videoId)
        }
        heldKey = null
        heldVideoId = null
    }
}
