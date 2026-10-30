package com.music.bitchord.ui

import com.music.bitchord.data.net.isCurrentSearchResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs committed search requests independently of one another.
 *
 * A single serial collector made a newer query wait for the previous request's
 * full network timeout before it even showed Loading, and let requests that had
 * already been superseded spend a round trip. Each request is launched on its
 * own here; one that is no longer the newest is skipped before it touches the
 * network. Requests already in flight are allowed to finish rather than being
 * cancelled, so a pooled HTTP connection is never torn down under the request
 * that follows it — the reason [collectLatest] was wrong here.
 */
internal class SearchRequestPipeline(
    private val scope: CoroutineScope,
    private val newestRequestId: () -> Long,
) {

    fun submit(requestId: Long, work: suspend (isCurrent: () -> Boolean) -> Unit) {
        scope.launch {
            val isCurrent = { isCurrentSearchResponse(requestId, newestRequestId()) }
            if (!isCurrent()) return@launch
            work(isCurrent)
        }
    }
}
