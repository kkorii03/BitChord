package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The best row one source's walk has already opened, kept where the source's
 * timeout in SourceResolver.bestAcross can still read it.
 *
 * A patient walk (an upgrade, hq-ahead, a download) opens the remaining
 * lossless rows after one has answered, to keep the best of them. Phone 11-19
 * 02:08 (vampire aHmg0jsmNhg): the second look opened row 1 at once, then row 2
 * took an 11 s cold read and row 3 was still open when the 12 s timeout cut
 * the walk, and the open row 1 went with it: "no better copy on any source",
 * and the timeout rested the addon for 30 minutes.
 */
internal class KoRowInHand : AbstractCoroutineContextElement(KoRowInHand) {
    companion object Key : CoroutineContext.Key<KoRowInHand>

    @Volatile
    var row: Pair<Song, SourceStream>? = null
}
