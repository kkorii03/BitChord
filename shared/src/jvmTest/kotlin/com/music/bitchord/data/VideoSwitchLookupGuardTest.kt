package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the v1.9 merge dropped from the video switch's search fallback:
 * a row that lost its header must not search an empty question, a miss must
 * not be searched again on every play, two callers asking at once must share
 * one lookup, and the switch must keep the 1.8 matcher and query-length log.
 */
class VideoSwitchLookupGuardTest {

    @Test
    fun `a lookup with a blank title is skipped, not remembered as missing`() = runBlocking {
        val id = "blank-title-guard-${System.nanoTime()}"
        val song = Song(videoId = id, title = " ", artist = "Artist", thumbnailUrl = null)

        assertNull(YtMusicRepository.resolveVideo(song))
        assertFalse(
            missingVideoVersions().containsKey(id),
            "a skipped lookup must not be cached as a missing video version",
        )
    }

    @Test
    fun `a remembered miss does not search again`() = runBlocking {
        val id = "missing-guard-${System.nanoTime()}"
        val song = Song(videoId = id, title = "...", artist = "Artist", thumbnailUrl = null)
        assertTrue(emptyQueries(song), "the test song must not reach the network")
        missingVideoVersions()[id] = true

        val messages = mutableListOf<String>()
        val previous = DebugLog.sink
        DebugLog.sink = DebugLog.Sink { _, _, message, _ -> messages += message }
        try {
            assertNull(YtMusicRepository.resolveVideo(song))
        } finally {
            DebugLog.sink = previous
            missingVideoVersions().remove(id)
        }

        assertTrue(
            messages.any { "was already missing" in it },
            "a remembered miss must short-circuit the search",
        )
    }

    @Test
    fun `two callers asking at once share one lookup`() = runBlocking {
        val id = "shared-lookup-${System.nanoTime()}"
        val song = Song(videoId = id, title = "...", artist = "Artist", thumbnailUrl = null)
        assertTrue(emptyQueries(song), "the test song must not reach the network")
        val found = Song(videoId = "found-$id", title = "Found", artist = "Artist", thumbnailUrl = null)
        val pending = CompletableDeferred<Song?>()
        pending.complete(found)
        inFlightVideoLookups()[id] = pending
        try {
            assertEquals(found, YtMusicRepository.resolveVideo(song))
        } finally {
            inFlightVideoLookups().remove(id)
        }
    }

    @Test
    fun `the video switch chooses with the video-switch matcher and logs no query text`() {
        val source = repositorySource()
        val resolveVideo = source
            .substringAfter("suspend fun resolveVideo(song: Song): Song? {")
            .substringBefore("fun cachedSongVersionFor")
        assertTrue(
            resolveVideo.contains("TrackMatcher.bestForVideoSwitch("),
            "a switch search must reject fancams and stage uploads through bestForVideoSwitch",
        )
        assertTrue(
            resolveVideo.contains("SearchKind.VIDEO_SWITCH"),
            "the search must be logged as a video switch",
        )
        assertTrue(
            resolveVideo.contains("queryLength=\${query.length}"),
            "the log must carry the query length, not the listener's words",
        )
        assertFalse(
            resolveVideo.contains("(\$query)"),
            "the log must not print the search query",
        )
    }

    private fun repositorySource(): String {
        val file = listOf(
            "src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt",
            "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt",
            "../shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt",
        ).map(::File).firstOrNull(File::exists)
        assertTrue(
            file != null,
            "YtMusicRepository.kt must be reachable from the test working directory",
        )
        return file!!.readText()
    }

    private fun emptyQueries(song: Song): Boolean =
        TrackMatcher.queries(TrackMatcher.targetOf(song)).isEmpty()

    private fun missingVideoVersions(): MutableMap<String, Boolean> {
        val field = YtMusicRepository::class.java.getDeclaredField("videoVersionMissing")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(YtMusicRepository) as MutableMap<String, Boolean>
    }

    private fun inFlightVideoLookups(): MutableMap<String, CompletableDeferred<Song?>> {
        val field = YtMusicRepository::class.java.getDeclaredField("videoVersionLookups")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(YtMusicRepository) as MutableMap<String, CompletableDeferred<Song?>>
    }
}