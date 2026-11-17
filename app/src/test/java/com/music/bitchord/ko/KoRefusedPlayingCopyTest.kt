package com.music.bitchord.ko

import android.net.FakeUri
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 11-17 (RG1-1). A copy refused while it plays (the words audit hears
 * the copy that is sounding) dropped out of the cache's choice at once, so the
 * next seek reopened the same plain URI on another entry at the old copy's
 * byte offset: a FLAC offset read from an Opus file, noise or an error.
 *
 * The cache's key path is modelled the way [KoLosslessCache.completeUpgradeRendition]
 * wires it (pinned on the source below): the choice of the moment, kept on the
 * entry this data source read before while that entry is whole and refused.
 */
class KoRefusedPlayingCopyTest {

    private val tracker = KoPlaybackReadTracker()
    private var refused = false
    private val complete = setOf("abc#hifi")

    /** What a plain open of `abc` is keyed to, as phoneKey asks it. */
    private fun keyOf(uri: Uri): String {
        val videoId = uri.getQueryParameter("v")!!
        val chosen = if (refused) null else "$videoId#hifi"
        return koKeepRefusedPlayingCopy(chosen, tracker.reopenPinFor(videoId), { it in complete }, { refused })
            ?: videoId
    }

    /** Stands in for the CacheDataSource, which resolves the key inside its own open. */
    private inner class CacheStandIn : DataSource {
        val opened = mutableListOf<String>()
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            opened += keyOf(dataSpec.uri)
            return 0L
        }
        override fun getUri(): Uri? = null
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun close() = Unit
    }

    private fun source(cache: CacheStandIn) = KoCachingDataSource(
        cacheDs = cache,
        upstreamDs = cache,
        cacheKeyOf = ::keyOf,
        notePlayedBytes = { _, _ -> },
        tracker = tracker,
        onReaderReleased = {},
    )

    private fun spec(url: String, position: Long = 0L) =
        DataSpec.Builder().setUri(FakeUri(url)).setPosition(position).build()

    @Test
    fun `a seek after the playing copy is refused stays on that copy`() {
        val cache = CacheStandIn()
        val player = source(cache)
        player.open(spec("bitchord://watch?v=abc"))
        player.close()
        refused = true
        // The seek: same data source, same plain URI, a later byte offset.
        player.open(spec("bitchord://watch?v=abc", position = 1_000_000L))
        player.close()
        assertEquals(listOf("abc#hifi", "abc#hifi"), cache.opened)
        // Nothing is left pinned for other opens on this thread.
        assertNull(tracker.reopenPinFor("abc"))
    }

    @Test
    fun `the next play of the song does not open the refused copy`() {
        source(CacheStandIn()).apply { open(spec("bitchord://watch?v=abc")); close() }
        refused = true
        val replay = CacheStandIn()
        source(replay).apply { open(spec("bitchord://watch?v=abc")); close() }
        assertEquals(listOf("abc"), replay.opened)
    }

    @Test
    fun `a copy gone from the disk is not asked for again`() {
        assertEquals(null, koKeepRefusedPlayingCopy(null, "abc#hifi", { false }, { true }))
        assertEquals("abc#alt", koKeepRefusedPlayingCopy("abc#alt", "abc#hifi", { true }, { false }))
        assertEquals("abc#hifi", koKeepRefusedPlayingCopy(null, "abc#hifi", { true }, { true }))
        assertEquals("abc#alt", koKeepRefusedPlayingCopy("abc#alt", null, { true }, { true }))
    }

    @Test
    fun `a tagged open pins nothing for a later plain open`() {
        val cache = CacheStandIn()
        val player = source(cache)
        player.open(spec("bitchord://watch?v=abc&q=hifi"))
        player.close()
        refused = true
        player.open(spec("bitchord://watch?v=abc"))
        assertEquals("abc", cache.opened.last())
    }

    @Test
    fun `the cache's key path keeps the copy this play reads`() {
        val root = File(".").canonicalFile
        val file = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/ko/KoLosslessCache.kt") }
            .first { it.exists() }
        val choice = file.readText().substringAfter("fun completeUpgradeRendition(")
            .substringBefore("private fun renditionCandidates(")
        assertTrue(choice.contains("koKeepRefusedPlayingCopy(chosen, tracker.reopenPinFor(videoId), isComplete, isRefused)"))
        // The deferral that guards a prefetch made during the play still answers first.
        assertTrue(choice.indexOf("upgradeKeyDeferred") < choice.indexOf("koKeepRefusedPlayingCopy"))
    }
}
