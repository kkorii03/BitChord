package com.music.bitchord.ko

import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.DirectSourceGate
import com.music.bitchord.playback.StreamChoice
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phone 11-18 17:11 (fqqcXP1D0Fw, first track of a fresh process): "TIMING first
 * audio: 8364ms" with 192 s of the row's YouTube copy on disk. 1.6 s of it was
 * the listener pressing play on a restored queue; the other 6.8 s was the Direct
 * settle in AudioCache racing the addon against a 5.5 s YouTube walk on the
 * loader thread before the cached bytes were read.
 *
 * Old code: [KoCachedPrefixStart] and [KoFirstAudioSplit] do not exist (this
 * class does not compile), and [theDirectSettleAsksBeforeItWaits] fails on its
 * settleDirectEntry, which went straight to the race.
 */
class KoColdStartTest {

    private val id = "cold-prefix-1"
    private val target = TrackMatcher.Target("Cold One", "Eljohnzc", durationSec = 220)
    private val enough = KoCachedPrefixStart.MIN_AHEAD_BYTES
    private val defaultBehind = KoCachedPrefixStart.behind
    private val launched = mutableListOf<String>()

    @Before
    fun reset() {
        KoCachedPrefixStart.resetForTest()
        KoFirstAudioSplit.resetForTest()
        KoDirectLead.reset()
        StreamChoice.forget(id)
        Downloads.setSavedMetadata(emptyMap())
        launched.clear()
        KoCachedPrefixStart.behind = { videoId, _, _ -> launched += videoId }
        DirectSourceGate.onPlaying(setOf(id))
    }

    @After
    fun restore() {
        KoCachedPrefixStart.behind = defaultBehind
        DirectSourceGate.onPlaying(emptySet())
        KoCachedPrefixStart.resetForTest()
        KoFirstAudioSplit.resetForTest()
        StreamChoice.forget(id)
        Downloads.setSavedMetadata(emptyMap())
    }

    // ---- the gate ----

    @Test
    fun theGateNeedsAPlayingRowOnYouTubesClockWithEnoughBytesAndNoAddonInHand() {
        assertTrue(KoCachedPrefixStart.eligible(true, true, false, enough, false))
        assertFalse("prepared ahead keeps the old wait", KoCachedPrefixStart.eligible(false, true, false, enough, false))
        assertFalse("the lyrics run on another clock", KoCachedPrefixStart.eligible(true, false, false, enough, false))
        assertFalse("a proved addon starts at once", KoCachedPrefixStart.eligible(true, true, true, enough, false))
        assertFalse("too little ahead of the read", KoCachedPrefixStart.eligible(true, true, false, enough - 1, false))
        assertFalse("a hole at the read", KoCachedPrefixStart.eligible(true, true, false, -1, false))
        assertTrue("a whole copy needs no walk", KoCachedPrefixStart.eligible(true, true, false, 0, true))
    }

    // ---- takesOver ----

    @Test
    fun thePlayingRowReadsItsCacheAndTheSettleStartsOnceBehindIt() {
        assertTrue(KoCachedPrefixStart.takesOver(id, target, 3_100_000, complete = false))
        assertEquals(listOf(id), launched)
        assertTrue(KoCachedPrefixStart.startedForTest(id))

        // A seek or the continuation reopens: still the cache, no second settle,
        // and never the addon branch that drops the entry under its reader.
        assertTrue(KoCachedPrefixStart.takesOver(id, target, 0, complete = false))
        assertEquals(listOf(id), launched)
    }

    @Test
    fun aRowThatIsNotPlayingOrHasTooLittleCachedKeepsTheOldSettle() {
        assertFalse(KoCachedPrefixStart.takesOver(id, target, enough - 1, complete = false))
        DirectSourceGate.onPlaying(setOf("someone-else"))
        assertFalse(KoCachedPrefixStart.takesOver(id, target, 3_100_000, complete = false))
        assertTrue(launched.isEmpty())
        assertFalse(KoCachedPrefixStart.startedForTest(id))
    }

    @Test
    fun aRowOnASubstitutesClockKeepsTheOldSettle() {
        StreamChoice.remember(
            id,
            SourceStream(url = "https://addon.example/t.flac", sourceConfigId = "src", trackId = "trk"),
            substituted = true,
        )
        assertFalse(KoCachedPrefixStart.takesOver(id, target, 3_100_000, complete = false))
        assertTrue(launched.isEmpty())
    }

    @Test
    fun theRowStoppingBeingAudibleClearsItsMark() {
        assertTrue(KoCachedPrefixStart.takesOver(id, target, enough, complete = false))
        DirectSourceGate.onPlaying(setOf("next-row"))
        assertFalse(KoCachedPrefixStart.startedForTest(id))
        DirectSourceGate.onPlaying(setOf(id))
        assertTrue(KoCachedPrefixStart.takesOver(id, target, enough, complete = false))
        assertEquals(listOf(id, id), launched)
    }

    // ---- the settle behind ----

    @Test
    fun theWalkPinsYouTubeAndTheLookupGoesToTheSecondLook() = runBlocking {
        val lookup = CompletableDeferred<SourceStream?>()
        var pinned: String? = null
        var handed: Deferred<SourceStream?>? = null
        var handOvers = 0
        val pinnedYouTube = KoCachedPrefixStart.settleBehind(
            videoId = id,
            lookup = { lookup },
            walk = { "https://yt.example/a" },
            stillWanted = { true },
            pin = { pinned = it },
            handOver = { handed = it; handOvers++ },
        )
        assertTrue(pinnedYouTube)
        assertEquals("https://yt.example/a", pinned)
        assertSame(lookup, handed)
        assertEquals(1, handOvers)
    }

    @Test
    fun aFailedWalkPinsNothingAndTheCachedCopyPlaysOn() = runBlocking {
        var pinned = false
        var handedOver = false
        val pinnedYouTube = KoCachedPrefixStart.settleBehind(
            videoId = id,
            lookup = { CompletableDeferred<SourceStream?>(null) },
            walk = { error("every client refused") },
            stillWanted = { true },
            pin = { pinned = true },
            handOver = { handedOver = true },
        )
        assertFalse(pinnedYouTube)
        assertFalse(pinned)
        assertFalse(handedOver)
    }

    @Test
    fun aRowNoLongerWaitingIsNotPinned() = runBlocking {
        var pinned = false
        val pinnedYouTube = KoCachedPrefixStart.settleBehind(
            videoId = id,
            lookup = { null },
            walk = { "https://yt.example/a" },
            stillWanted = { false },
            pin = { pinned = true },
            handOver = {},
        )
        assertFalse(pinnedYouTube)
        assertFalse(pinned)
    }

    @Test
    fun aRefusedRowHandsOverNoLookup() = runBlocking {
        var handed: Deferred<SourceStream?>? = CompletableDeferred()
        assertTrue(
            KoCachedPrefixStart.settleBehind(
                videoId = id,
                lookup = { null },
                walk = { "https://yt.example/a" },
                stillWanted = { true },
                pin = {},
                handOver = { handed = it },
            ),
        )
        assertNull(handed)
    }

    @Test
    fun theDirectSettleAsksBeforeItWaits() {
        val text = source("src/main/java/com/music/bitchord/playback/AudioCache.kt")
        val body = text.substringAfter("private fun settleDirectEntry(").substringBefore("dropYouTubeEntry(key, videoId)")
        val ask = body.indexOf("KoCachedPrefixStart.takesOver(")
        val race = body.indexOf("KoFirstSound.settleDirect(")
        assertTrue("the settle asks the cached copy first", ask in 0 until race)
        assertTrue(
            "the open passes where its read starts",
            text.contains("settleDirectEntry(dataSpec.uri, dataSpec.position)"),
        )
    }

    // ---- the first-audio split ----

    @Test
    fun theFirstAudioLineNamesALatePress() {
        KoFirstAudioSplit.pressed(48_510, 50_084)
        assertEquals(
            " (6790ms since play pressed 1574ms after selection)",
            KoFirstAudioSplit.suffix(48_510, 56_874),
        )
    }

    @Test
    fun aPressThatIsPartOfTheSelectionAddsNothing() {
        KoFirstAudioSplit.pressed(1_000, 1_100)
        assertEquals("", KoFirstAudioSplit.suffix(1_000, 3_000))
        assertEquals("no press at all", "", KoFirstAudioSplit.suffix(9_000, 10_000).also { KoFirstAudioSplit.resetForTest() })
        assertEquals("", KoFirstAudioSplit.suffix(1_000, 3_000))
    }

    @Test
    fun aPressForAnEarlierSelectionIsIgnoredAndTheFirstPressIsKept() {
        KoFirstAudioSplit.pressed(1_000, 5_000)
        assertEquals("another selection", "", KoFirstAudioSplit.suffix(2_000, 9_000))
        KoFirstAudioSplit.pressed(1_000, 7_000) // pause, play again before the first note
        assertEquals(" (4000ms since play pressed 4000ms after selection)", KoFirstAudioSplit.suffix(1_000, 9_000))
        KoFirstAudioSplit.pressed(null, 8_000)
        assertEquals(" (4000ms since play pressed 4000ms after selection)", KoFirstAudioSplit.suffix(1_000, 9_000))
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "$it$path") }.first { it.exists() }
            .readText().replace("\r\n", "\n")
    }
}
