package com.music.bitchord.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review F1 (11-17). A song-version swap (video row -> its song version, or
 * back) is one listen under a new id. The swap paths live inside the playback
 * service, which no unit test can run without two real players, so their wiring
 * is pinned on the source, as HqVersionGuardWiringTest does. The record itself
 * is tested in ListeningRecorderVersionSwapTest.
 */
class VersionSwapListenWiringTest {

    private val service: String by lazy {
        val root = File(".").canonicalFile
        listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/playback/PlaybackService.kt") }
            .first { it.exists() }
            .readText()
    }

    private fun body(start: String, end: String) = service.substringAfter(start).substringBefore(end)

    @Test
    fun `the smooth swap's handover is booked as a version swap`() {
        val adopt = body("internal fun adoptPlayerForVersionSwap(", "/** Reopens the current song through YouTube")
        assertTrue("the old id is read before the outgoing player is cleared",
            adopt.indexOf("val swappedFrom = outgoing.currentMediaItem?.mediaId") in 0 until adopt.indexOf("outgoing.clearMediaItems()"))
        assertTrue(adopt.contains("versionSwapFrom = swappedFrom"))
        // The standby-failed fallback of the smooth swap books it the same way.
        assertEquals(2, Regex("""versionSwapFrom = swappedFrom""").findAll(service).count())
    }

    @Test
    fun `a version swap skips the new-listen bookkeeping and carries it instead`() {
        val current = body("private fun onTrackBecameCurrent(", "AudioCache.onTrackBecameCurrent(")
        assertTrue(current.contains("versionSwapFrom: String? = null"))
        val tail = body("val ended = previousEnded", "if (exoPlayer.isPlaying && !sameTrackSwap) registerCurrentPlay()")
        val gate = tail.indexOf("!koCarryListenAcrossVersionSwap(versionSwapFrom,")
        assertTrue("the gate sits on the new-listen block", gate in 0 until tail.indexOf("PlaybackTracker.onTrackChanged()"))
        assertTrue(gate < tail.indexOf("ListeningRecorder.onStopped()"))
        assertTrue(gate < tail.indexOf("scrobbleManager?.onSongStart("))
    }

    @Test
    fun `the carry tells every listen record and the direct swap uses it before the replace`() {
        val carry = body("private fun koCarryListenAcrossVersionSwap(", "/** Remembers the facts the one-line summary")
        assertTrue(carry.contains("PlaybackTracker.onVersionSwapped(fromVideoId, to.videoId)"))
        assertTrue(carry.contains("postProgressWork { ListeningRecorder.onVersionSwapped(fromVideoId, to) }"))
        assertTrue(carry.contains("scrobbleManager?.onVersionSwapped(to)"))
        assertTrue(carry.contains("listenBrainzSong = to"))

        val direct = body("private fun swapCurrentToVersion(", "private fun toggleShuffleFromSession()")
        val told = direct.indexOf("koCarryListenAcrossVersionSwap(song.videoId, targetSong")
        assertTrue(told in 0 until direct.indexOf("activePlayer.replaceMediaItem("))
    }

    @Test
    fun `the smooth swap's no-standby replace carries the listen before the replace`() {
        // swappingMediaId is still set there, so onMediaItemTransition returns
        // early for the replace and nothing else books it (11-17 review).
        val replace = body("if (!canCrossfade || standbyPlayer == null) {", "return@launch")
        val told = replace.indexOf("koCarryListenAcrossVersionSwap(sourceSong.videoId, targetSong")
        assertTrue(told in 0 until replace.indexOf("activePlayer.replaceMediaItem("))
    }
}
