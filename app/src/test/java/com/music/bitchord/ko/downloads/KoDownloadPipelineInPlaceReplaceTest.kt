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

    /**
     * l1121 CLOCK-1: song S and its music video V share a YouTube-bytes file. A FLAC
     * with its own pair and a +1800 ms offset replaces S's file. The old copy only
     * moved V's uri, so V played the FLAC on the YouTube clock (~1.8 s off).
     */
    @Test
    fun `an alias of the replaced file takes the new file's clock and keeps its own row`() {
        val oldUri = "content://media/external/audio/media/11"
        val v = com.music.bitchord.download.SavedSongMetadata(
            videoId = "V", title = "Song (Official MV)", artist = "Artist", thumbnailUrl = "mv.jpg",
            uri = oldUri, downloadFormat = "OPUS · 160 kbps", dateAddedSeconds = 100L,
            soundSourceVideoId = "S", isVideo = true, hqTier = null, recordingVerified = false,
            wrongRecording = true,
        )
        val s = com.music.bitchord.download.SavedSongMetadata(
            videoId = "S", title = "Song", artist = "Artist", uri = uri, downloadFormat = "FLAC · 24/96",
            dateAddedSeconds = 50L, soundSourceVideoId = "S", isVideo = false,
            hqSourceConfigId = "addon", hqTrackId = "t9", hqOffsetMs = 1_800L, hqRate = 1.0,
            hqTier = "HI_RES", hqSourceTitle = "Song", hqSourceArtist = "Artist", recordingVerified = true,
        )
        val moved = KoDownloadPipeline.aliasOfReplacedFile(v, s, uri, "S")
        assertEquals(uri, moved.uri)
        assertEquals("addon", moved.hqSourceConfigId)
        assertEquals("t9", moved.hqTrackId)
        assertEquals(1_800L, moved.hqOffsetMs)
        assertEquals(1.0, moved.hqRate!!, 0.0)
        assertEquals("HI_RES", moved.hqTier)
        assertEquals("FLAC · 24/96", moved.downloadFormat)
        assertTrue("the new file was proved", moved.recordingVerified)
        assertFalse("the old file's wrong verdict went with it", moved.wrongRecording)
        assertEquals("the row's own title", "Song (Official MV)", moved.title)
        assertEquals("V", moved.videoId)
        assertEquals(true, moved.isVideo)
        assertEquals("mv.jpg", moved.thumbnailUrl)
        assertEquals(100L, moved.dateAddedSeconds)
        // The reverse: a FLAC alias replaced by YouTube's bytes drops the old pair, not keeps it.
        val flacAlias = s.copy(videoId = "S2", uri = oldUri)
        val youTubeFile = v.copy(videoId = "S", uri = uri, hqTier = null, wrongRecording = false)
        val back = KoDownloadPipeline.aliasOfReplacedFile(flacAlias, youTubeFile, uri, "S")
        assertNull(back.hqSourceConfigId)
        assertNull(back.hqOffsetMs)
    }

    /** l1121 CLOCK-1: a playing alias moved onto the new file has no verdict under its own id. */
    @Test
    fun `a moved alias starts through its record's alignment when the store has none`() {
        val meta = com.music.bitchord.download.SavedSongMetadata(
            videoId = "V", title = "t", artist = "a", uri = uri,
            hqSourceConfigId = "addon", hqTrackId = "t9", hqOffsetMs = 1_800L, hqRate = 1.0,
        )
        // Old code: store-only lookup -> no map -> raw position (a 1.8 s jump).
        assertEquals(1_800L to 1.0, com.music.bitchord.ko.KoRecordedClock.fileAlignment(null, meta))
        assertEquals("the store's verdict wins", 900L to 1.0, com.music.bitchord.ko.KoRecordedClock.fileAlignment(900L to 1.0, meta))
        assertNull("a wrong recording carries none", com.music.bitchord.ko.KoRecordedClock.fileAlignment(null, meta.copy(wrongRecording = true)))
        assertNull("YouTube's bytes have no pair", com.music.bitchord.ko.KoRecordedClock.fileAlignment(null, meta.copy(hqSourceConfigId = null)))
        assertNull(com.music.bitchord.ko.KoRecordedClock.fileAlignment(null, null))
        val handler = File("src/main/java/com/music/bitchord/ko/KoPlaybackTranslationAhead.kt").readText()
            .substringAfter("internal fun PlaybackService.koUseVerifiedDownload(")
        assertTrue(handler.contains("KoRecordedClock.fileAlignment(stored, meta)"))
    }

    @Test
    fun `aliases are the other ids on the replaced file`() {
        val old = "content://media/external/audio/media/11"
        val saved = mapOf("S" to uri, "F" to uri, "V" to old, "W" to uri, "other" to "content://x/2")
        assertEquals(
            "an old path moves; a same-path id only when the bytes were rewritten",
            setOf("V"),
            KoDownloadPipeline.aliasesOfReplacedFile(saved, setOf("S", "F"), setOf(old), uri, replacing = false),
        )
        assertEquals(
            setOf("W"),
            KoDownloadPipeline.aliasesOfReplacedFile(
                mapOf("S" to uri, "W" to uri), setOf("S"), setOf(uri), uri, replacing = true,
            ),
        )
        assertTrue(
            "a plain adopt rewrote nothing",
            KoDownloadPipeline.aliasesOfReplacedFile(mapOf("S" to uri, "W" to uri), setOf("S"), setOf(uri), uri, replacing = false).isEmpty(),
        )
        val pipeline = File("src/main/java/com/music/bitchord/ko/downloads/KoDownloadPipeline.kt").readText()
            .substringAfter("suspend fun afterRemember(")
        assertTrue("their version alignment goes", pipeline.contains("if (dropTiming) fileAliases.forEach(::forgetAlignment)"))
        assertTrue("their record takes the file's", pipeline.contains("aliasOfReplacedFile(value, file, uri.toString(), soundSourceVideoId)"))
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
