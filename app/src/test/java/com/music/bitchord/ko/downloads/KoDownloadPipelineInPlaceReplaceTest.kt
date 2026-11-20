package com.music.bitchord.ko.downloads

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review D3: a deliberate replacement written back over the same path keeps the
 * old uri. The old rule (`previousUris.any { it != newUri } || suspects`) said
 * "not replaced" for it, so the old recording's AI timing, heard words,
 * automatic recheck sheet and "already checked" mark stayed on the new bytes.
 */
class KoDownloadPipelineInPlaceReplaceTest {

    private val uri = "file:///data/user/0/com.music.bitchord/files/Music/a.flac"

    @Test
    fun `same uri rewritten by a replacement drops the old timing`() {
        // Old code: previousUris={uri}, newUri=uri, no suspects -> false.
        assertTrue(
            KoDownloadPipeline.dropsOldTiming(
                previousUris = setOf(uri),
                newUri = uri,
                replacing = true,
                suspects = emptyList(),
            ),
        )
    }

    @Test
    fun `same uri without a replacement keeps the timing`() {
        assertFalse(
            "an adopt rewrote nothing",
            KoDownloadPipeline.dropsOldTiming(
                previousUris = setOf(uri),
                newUri = uri,
                replacing = false,
                suspects = emptyList(),
            ),
        )
    }

    /**
     * u1121 LIBRARY-3: a replacement committed while its old file was sounding.
     * The record and the lyric clock named the new file while the player kept
     * reading the old one; nothing moved the player.
     */
    @Test
    fun `a player still on the replaced file moves, nothing else does`() {
        val old = "content://media/external/audio/media/11"
        val event = KoDownloadPipeline.FileReplaced(setOf("song", "mv"), setOf(old), uri, mapOf("song" to ("addon" to "t1")))
        assertTrue(KoDownloadPipeline.movesPlayer(event, "song", old))
        assertTrue("an alias filed under the same file", KoDownloadPipeline.movesPlayer(event, "mv", old))
        assertFalse("already on the new file", KoDownloadPipeline.movesPlayer(event, "song", uri))
        assertTrue("an m4a record plays through its file path", KoDownloadPipeline.movesPlayer(event, "song", "file:///storage/emulated/0/Music/old.m4a"))
        assertFalse("a stream is not the old file", KoDownloadPipeline.movesPlayer(event, "song", "bitchord://watch?v=song"))
        assertFalse("another song", KoDownloadPipeline.movesPlayer(event, "other", old))
        assertFalse(KoDownloadPipeline.movesPlayer(event, null, old))
        val samePath = KoDownloadPipeline.FileReplaced(setOf("song"), setOf(uri), uri, emptyMap())
        assertFalse("a same-path rewrite has nothing to move onto", KoDownloadPipeline.movesPlayer(samePath, "song", uri))
    }

    @Test
    fun `the old file's clock is its own pair, an alias takes the shared one`() {
        val event = KoDownloadPipeline.FileReplaced(
            setOf("song", "mv", "stale"),
            setOf("file:///old.m4a"),
            uri,
            mapOf("song" to ("addon" to "t1"), "mv" to ("addon" to "t1")),
        )
        assertEquals("addon" to "t1", event.oldPairOf("song"))
        assertEquals("addon" to "t1", event.oldPairOf("stale"))
        val youTubeBytes = KoDownloadPipeline.FileReplaced(setOf("song"), setOf("file:///old.m4a"), uri, mapOf("song" to null))
        assertNull("a YouTube download keeps YouTube's clock", youTubeBytes.oldPairOf("song"))
    }

    @Test
    fun `the replacement is announced and the service moves the playing file`() {
        val pipeline = File("src/main/java/com/music/bitchord/ko/downloads/KoDownloadPipeline.kt").readText()
        val after = pipeline.substringAfter("suspend fun afterRemember(")
        val emit = after.indexOf("_replaced.tryEmit(FileReplaced(ids + staleIds, previous.uris, uri.toString(), previous.pairs))")
        assertTrue("afterRemember announces the replacement", emit >= 0)
        assertTrue("before the old file is deleted", emit < after.indexOf("DownloadStore.delete(context, old.toUri())"))
        val service = File("src/main/java/com/music/bitchord/playback/PlaybackService.kt").readText()
        assertTrue(service.contains("KoDownloadPipeline.replaced.collect { koOnSavedFileReplaced(it) }"))
        val handler = File("src/main/java/com/music/bitchord/ko/KoPlaybackTranslationAhead.kt").readText()
            .substringAfter("internal fun PlaybackService.koOnSavedFileReplaced(")
            .substringBefore("internal fun PlaybackService.koUseVerifiedDownload(")
        assertTrue("refused during a blend", handler.contains("crossfade?.isTransitioning() == true"))
        assertTrue("only onto a verified file", handler.contains("!Downloads.isRecordingVerified(id)"))
        assertTrue("from the old file's own clock", handler.contains("koUseVerifiedDownload(live, item, live.currentPosition, playing = old)"))
    }

    @Test
    fun `a new path or a length suspect still drops it as before`() {
        assertTrue(
            KoDownloadPipeline.dropsOldTiming(setOf("file:///old.m4a"), uri, replacing = false, suspects = emptyList()),
        )
        assertTrue(
            KoDownloadPipeline.dropsOldTiming(setOf(uri), uri, replacing = false, suspects = listOf("mv-id")),
        )
        assertFalse(
            "a first download has nothing to drop",
            KoDownloadPipeline.dropsOldTiming(emptySet(), uri, replacing = false, suspects = emptyList()),
        )
    }
}
