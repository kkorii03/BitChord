package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.ko.ui.addInChunks
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spotify importer must score every search hit rather than take the first,
 * and must only fall back to that first hit when nothing matched.
 */
class SpotifyImportMatchTest {

    private fun song(id: String, title: String, artist: String, duration: String?) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    private val target = TrackMatcher.Target(
        title = "Song",
        artist = "Artist",
        durationSec = 200,
        album = "Album",
    )

    @Test
    fun theBestScoredCandidateWinsEvenWhenItIsNotFirst() {
        val wrongTake = song("wrong", "Song", "Someone Else", "3:00")
        val rightTake = song("right", "Song", "Artist", "3:20")
        val match = SpotifyImporter.bestMatch(listOf(wrongTake, rightTake), target)
        assertEquals("right", match.song?.videoId)
        assertFalse(match.loose)
    }

    @Test
    fun anUnrelatedFirstHitIsOnlyTheLooseFallback() {
        val unrelated = song("other", "Different Track", "Nobody", "2:10")
        val match = SpotifyImporter.bestMatch(listOf(unrelated), target)
        assertEquals("other", match.song?.videoId)
        assertTrue(match.loose)
    }

    @Test
    fun noCandidatesIsNoMatchRatherThanALooseOne() {
        val match = SpotifyImporter.bestMatch(emptyList(), target)
        assertNull(match.song)
        assertFalse(match.loose)
    }

    // ---- Review F2b-4: a refused 50-song chunk is counted, not lost unseen ----

    @Test
    fun aRefusedChunkIsCountedAndEveryChunkIsStillTried() = runBlocking {
        val ids = List(150) { "v$it" }
        val asked = mutableListOf<List<String>>()
        val missing = addInChunks(ids) { chunk ->
            asked += chunk
            when (asked.size) {
                2 -> Result.failure(IllegalStateException("refused"))
                // YouTube's answer may name fewer rows than it added; that is
                // not a refusal (see Innertube.addToPlaylist).
                3 -> Result.success(chunk.drop(1).associateWith { "set-$it" })
                else -> Result.success(chunk.associateWith { "set-$it" })
            }
        }
        assertEquals(listOf(50, 50, 50), asked.map { it.size })
        assertEquals(50, missing)
    }

    @Test
    fun nothingLeftToAddIsNothingMissing() = runBlocking {
        val missing = addInChunks(emptyList()) { error("no request for an empty tail") }
        assertEquals(0, missing)
    }

    @Test
    fun theImportReportsTheRefusedCountInsteadOfDroppingIt() {
        val root = File(".").canonicalFile
        val base = if (File(root, "app/src/main").exists()) "app/src/main" else "src/main"
        val vm = File(root, "$base/java/com/music/bitchord/ui/MainViewModel.kt").readText()
        val create = vm.substringAfter("fun createPlaylistWithVideoIds(").substringBefore("\n    fun ")
        assertTrue("the chunks after the first 50 must go through addInChunks", create.contains("addInChunks("))
        assertFalse("a chunk's Result must not be thrown away", create.contains(".chunked(50).forEach"))
        assertTrue("the card counts only the songs that were added", create.contains("videoIds.size - missing"))
        assertTrue("the caller is told how many were refused", create.contains("created.title, false, missing)"))
        val activity = File(root, "$base/java/com/music/bitchord/MainActivity.kt").readText()
        assertTrue("the import notice says how many were refused", activity.contains("R.string.spotify_import_partial"))
    }
}