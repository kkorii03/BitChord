package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.ChromaAlignment
import java.util.concurrent.ConcurrentHashMap

/**
 * [KO] A high-quality copy whose opening lines up with the YouTube song version
 * only along a bent path (review NR2b-1, 11-17).
 *
 * Two copies of one master always align as one straight line. A bend inside
 * the ninety compared seconds is a cut, an insert or a different edit, and the
 * verdict store can only hold one offset and one rate: the swap landing point,
 * the revert, the swap's length proof and the lyric clock would all be off by
 * the cut for the rest of the song. So such a match is left unmeasured — no
 * swap now, nothing stored — rather than refused: a refusal is kept and can
 * condemn a saved file, and a bend read into a noisy but right copy must not
 * throw that copy away for good.
 */
object KoHqWarp {

    /** Whether a match the policy [accepted] is bent and must not be stored as one line. */
    fun bent(accepted: Boolean, result: ChromaAlignment.Result): Boolean = accepted && !result.linear

    /**
     * Downloaded files measured bent in this app run. The answer comes from the
     * bytes, so a second decode would only say it again; memory only, so the
     * next run measures once more.
     */
    private val files = ConcurrentHashMap.newKeySet<String>()

    fun noteFile(youtubeId: String, uri: String) {
        if (files.size >= MAX_FILES) files.firstOrNull()?.let(files::remove)
        files += key(youtubeId, uri)
    }

    fun fileNoted(youtubeId: String, uri: String): Boolean = key(youtubeId, uri) in files

    fun clearForTest() = files.clear()

    private fun key(youtubeId: String, uri: String) = youtubeId + '\u0001' + uri

    private const val MAX_FILES = 256
}
