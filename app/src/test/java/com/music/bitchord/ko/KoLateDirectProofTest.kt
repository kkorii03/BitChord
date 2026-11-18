package com.music.bitchord.ko

import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.DirectSourceGate
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.smart.HqVerificationStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * nextprep fix 3: a Direct proof that lands after the next row was settled on
 * YouTube, but before it sounds, used to be looked at only by the mid-song
 * swap. It is now held for the standby, which takes it when it arms or re-arms
 * on [KoLateDirectProof.landed], and is pinned only once the standby opened it.
 *
 * Also nextprep B4: the Direct proof asks the Automix clock check that every
 * other next-row pin asks ([koDirectOnClock]).
 *
 * Old code: neither [KoLateDirectProof] nor [koDirectOnClock] exists.
 */
class KoLateDirectProofTest {

    private val flac = StreamFormat("flac", bitDepth = 16, sampleRateHz = 44_100)
    private fun telegram(id: String, format: StreamFormat = flac) =
        SourceStream(url = "https://Telegram/$id", format = format, sourceConfigId = "Telegram", trackId = id)

    @Before
    fun reset() {
        KoLateDirectProof.reset()
        SourceRegistry.configs.value = listOf(
            SourceConfig(id = "Telegram", kind = SourceKind.TELEGRAM, label = "Telegram", baseUrl = "https://Telegram", enabled = true),
            SourceConfig(id = "YouTube", kind = SourceKind.YOUTUBE, label = "YouTube", baseUrl = "", enabled = true),
        )
        DirectSourceGate.onPlaying(setOf("late-playing"))
    }

    @After
    fun restore() {
        KoLateDirectProof.reset()
        SourceRegistry.configs.value = emptyList()
        DirectSourceGate.onPlaying(emptySet())
    }

    @Test
    fun aLosslessProofLandingBeforeTheRowSoundsIsHeldOnceForTheStandby() {
        val id = "late-next-1"
        val before = KoLateDirectProof.landed.value
        val running = CompletableDeferred<SourceStream?>()
        KoLateDirectProof.watch(id, running) { false }
        assertNull("nothing until the lookup lands", KoLateDirectProof.take(id))

        running.complete(telegram("tg-1"))

        assertTrue("the standby's refresh is told", KoLateDirectProof.landed.value > before)
        assertNull("only for its own row", KoLateDirectProof.take("late-other"))
        assertEquals("https://Telegram/tg-1", KoLateDirectProof.take(id)?.url)
        assertNull("taken once", KoLateDirectProof.take(id))
    }

    @Test
    fun anAudibleRowALossyCopyOrYouTubeIsLeftToTheMidSongSwap() {
        assertFalse("audible: the second look swaps it", KoLateDirectProof.offer("late-2", telegram("tg-2"), audible = true))
        assertFalse(
            "a lossy copy is not worth reopening the standby",
            KoLateDirectProof.offer("late-2", telegram("tg-2", StreamFormat("aac", kbps = 256)), audible = false),
        )
        assertFalse(
            "YouTube's own stream",
            KoLateDirectProof.offer("late-2", SourceStream(url = "https://yt/late-2", format = flac, sourceConfigId = "YouTube"), audible = false),
        )
        assertFalse("an empty lookup", KoLateDirectProof.offer("late-2", null, audible = false))
        assertNull(KoLateDirectProof.take("late-2"))

        val running = CompletableDeferred<SourceStream?>()
        KoLateDirectProof.watch("late-2b", running) { true }
        running.complete(telegram("tg-2b"))
        assertNull("audible by the time it landed", KoLateDirectProof.take("late-2b"))
    }

    @Test
    fun aRowThatStartedOrLeftTheQueueLosesItsHeldProof() {
        assertTrue(KoLateDirectProof.offer("late-3", telegram("tg-3"), audible = false))
        DirectSourceGate.onPlaying(setOf("late-3"))
        assertNull("audible now: the mid-song swap takes it", KoLateDirectProof.take("late-3"))

        assertTrue(KoLateDirectProof.offer("late-4", telegram("tg-4"), audible = false))
        KoLateDirectProof.keepOnly("late-4")
        KoLateDirectProof.keepOnly("late-other")
        assertNull("the queue moved", KoLateDirectProof.take("late-4"))
    }

    @Test
    fun theStandbyOpeningItPinsItAndClosesTheSecondLook() {
        val id = "late-5"
        val stream = telegram("tg-5")
        QualityUpgrade.pending[id] = QualityUpgrade.Pending(TrackMatcher.Target("Late Five", "Eljohnzc"))
        try {
            StreamChoice.remember(id, SourceStream(url = "https://yt/$id", format = StreamFormat("opus"), sourceConfigId = "YouTube"), substituted = false)
            KoLateDirectProof.adopted(id, stream)
            assertEquals("the row is pinned to the copy the standby opened", stream.url, StreamChoice.of(id)?.url)
            assertTrue(StreamChoice.isSubstitute(id))
            assertFalse("no second swap for it mid-song", QualityUpgrade.isPending(id))
        } finally {
            StreamChoice.forget(id)
            QualityUpgrade.pending.remove(id)
        }
    }

    /** nextprep B4. */
    @Test
    fun theDirectProofRefusesAnOffClockLosslessCopyUnderAutomixBeforeItSounds() {
        fun verified(offsetMs: Long) = HqVerificationStore.Verified(offsetMs = offsetMs, rate = 1.0, matchPercent = 90, tier = HqTier.LOSSLESS_16)
        val tg = telegram("tg-6")
        assertFalse(koDirectOnClock("late-6", tg, verified(401L), audible = false, automix = true))
        assertTrue("on YouTube's clock", koDirectOnClock("late-6", tg, verified(0L), audible = false, automix = true))
        assertTrue("already audible: no blend cue", koDirectOnClock("late-6", tg, verified(401L), audible = true, automix = true))
        assertTrue("no Automix: nothing cues it", koDirectOnClock("late-6", tg, verified(401L), audible = false, automix = false))
        assertTrue(
            "a lossy copy keeps the old rule",
            koDirectOnClock("late-6", telegram("tg-6", StreamFormat("aac", kbps = 256)), verified(401L), audible = false, automix = true),
        )
    }

    @Test
    fun theHooksAreWired() {
        val gate = source("src/main/java/com/music/bitchord/playback/DirectSourceGate.kt")
        val started = gate.substringAfter("fun startedOnYouTube(").substringBefore("\n    }\n")
        assertTrue("startedOnYouTube watches the lookup it hands over", "KoLateDirectProof.watch(videoId, running)" in started)
        assertTrue("the drop clears a held proof", "KoLateDirectProof.keepOnly(" in gate.substringAfter("private fun dropAheadNotAdmitted() {").substringBefore("private fun Open.admits"))
        assertTrue("the gate's proof asks the clock", "koDirectOnClock(videoId, stream, it, audible = isPlaying(videoId))" in gate)

        val service = source("src/main/java/com/music/bitchord/playback/PlaybackService.kt")
        assertTrue("the armed refresh runs on a held proof too", "KoLateDirectProof.landed.drop(1)" in service)
        assertTrue("arming takes a held proof", "item.koLosslessAheadItemAtArm(this)" in service)

        val swap = source("src/main/java/com/music/bitchord/ko/KoPlaybackLosslessSwap.kt")
        val refresh = swap.substringAfter("internal fun PlaybackService.koRefreshArmedIncomingLossless() {")
        assertTrue("taken only while arming", refresh.indexOf("controller.isArming()") in 0 until refresh.indexOf("KoLateDirectProof.take("))
        assertTrue("pinned only after the re-arm", refresh.indexOf("controller.rearmIncoming(prepared)") < refresh.indexOf("KoLateDirectProof.adopted("))
    }

    private fun source(path: String): String {
        val root = File(".").canonicalFile
        return listOf("app/", "").map { File(root, "$it$path") }.first { it.exists() }
            .readText().replace("\r\n", "\n")
    }
}
