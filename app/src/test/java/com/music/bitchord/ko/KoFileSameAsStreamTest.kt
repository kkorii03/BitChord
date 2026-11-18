package com.music.bitchord.ko

import com.music.bitchord.playback.smart.LyricsTiming.Clock
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LOG6 R2 (11-17 phone FcXRwUsA6xs): the stream's -553 ms answer was lost when playback
 * moved to a download the check had just heard as the same recording at offset 0.
 */
class KoFileSameAsStreamTest {

    private val id = "FcXRwUsA6xs"
    private val uri = "content://downloads/FcXRwUsA6xs.m4a"

    @After
    fun clear() = KoFileSameAsStream.clearForTest()

    @Test
    fun aFileHeardIdenticalAtOffsetZeroCarriesTheStreamsAnswer() {
        KoFileSameAsStream.measured(id, uri, "v1", fresh = true, ok = true, offsetMs = 0L, rate = 1.0)
        val same = KoFileSameAsStream.heardSame(id, uri) { "v1" }
        assertTrue(same)
        assertTrue(KoFileSameAsStream.carries(Clock.YouTube, Clock.YouTube, playsFile = true, heardSame = same))
    }

    @Test
    fun anUnverifiedFileCarriesNothing() {
        // Never measured this run (the old code's only case: sibling=no-substitute-clock).
        val same = KoFileSameAsStream.heardSame(id, uri) { "v1" }
        assertFalse(same)
        assertFalse(KoFileSameAsStream.carries(Clock.YouTube, Clock.YouTube, playsFile = true, heardSame = same))
        // Measured from a stored verdict, not these bytes.
        KoFileSameAsStream.measured(id, uri, "v1", fresh = false, ok = true, offsetMs = 0L, rate = 1.0)
        assertFalse(KoFileSameAsStream.heardSame(id, uri) { "v1" })
        // Heard and refused.
        KoFileSameAsStream.measured(id, uri, "v1", fresh = false, ok = false, offsetMs = null, rate = null)
        assertFalse(KoFileSameAsStream.heardSame(id, uri) { "v1" })
    }

    @Test
    fun aNonZeroOffsetOrAnotherRateCarriesNothing() {
        KoFileSameAsStream.measured(id, uri, "v1", fresh = true, ok = true, offsetMs = 1_200L, rate = 1.0)
        assertFalse(KoFileSameAsStream.heardSame(id, uri) { "v1" })
        KoFileSameAsStream.measured(id, uri, "v1", fresh = true, ok = true, offsetMs = 0L, rate = 1.002)
        assertFalse(KoFileSameAsStream.heardSame(id, uri) { "v1" })
        assertFalse(KoFileSameAsStream.identical(-40L, 1.0))
        assertTrue(KoFileSameAsStream.identical(0L, 1.0))
    }

    @Test
    fun aLaterMeasureThatIsNotIdenticalTakesTheCarryBack() {
        KoFileSameAsStream.measured(id, uri, "v1", fresh = true, ok = true, offsetMs = 0L, rate = 1.0)
        KoFileSameAsStream.measured(id, uri, "v1", fresh = true, ok = true, offsetMs = 900L, rate = 1.0)
        assertFalse(KoFileSameAsStream.heardSame(id, uri) { "v1" })
        KoFileSameAsStream.measured(id, uri, "v1", fresh = true, ok = true, offsetMs = 0L, rate = 1.0)
        KoFileSameAsStream.measured(id, uri, "v1", fresh = false, ok = false, offsetMs = null, rate = null)
        assertFalse(KoFileSameAsStream.heardSame(id, uri) { "v1" })
    }

    @Test
    fun aReplacedFileOrAnotherUriCarriesNothing() {
        KoFileSameAsStream.measured(id, uri, "v1", fresh = true, ok = true, offsetMs = 0L, rate = 1.0)
        assertFalse(KoFileSameAsStream.heardSame(id, uri) { "v2" })
        assertFalse(KoFileSameAsStream.heardSame(id, "content://downloads/other.m4a") { "v1" })
        assertFalse(KoFileSameAsStream.heardSame(id, null) { "v1" })
    }

    @Test
    fun onlyYouTubesClockOnBothSidesAndTheFilePlayingCarry() {
        val alt = Clock.Substitute("tg", "42")
        assertFalse(KoFileSameAsStream.carries(Clock.YouTube, alt, playsFile = true, heardSame = true))
        assertFalse(KoFileSameAsStream.carries(alt, Clock.YouTube, playsFile = true, heardSame = true))
        assertFalse(KoFileSameAsStream.carries(Clock.YouTube, Clock.YouTube, playsFile = false, heardSame = true))
    }

    @Test
    fun anUntaggedStreamAnswerCarriesOntoAFileHeardToBeTheStream() {
        // log1819: 11-18 Espresso kIft-LUHHVA and 11-19 Dance The Night Z1yDjhXIDSc played
        // the stream's untagged answer (base=ai, playing=youtube), the check heard the file
        // at offset 1 / 0 ms, and the file played `sibling=untagged` with no sync.
        val alt = Clock.Substitute("744b174b", "qzdl:1")
        assertTrue(KoFileSameAsStream.carries(Clock.Unknown, Clock.YouTube, playsFile = true, heardSame = true))
        // An HQ download plays on its source pair's clock; heard at offset 0 it is the stream.
        assertTrue(KoFileSameAsStream.carries(Clock.Unknown, alt, playsFile = true, heardSame = true))
        // Still never without the file playing, the hearing, or a known playing clock.
        assertFalse(KoFileSameAsStream.carries(Clock.Unknown, Clock.YouTube, playsFile = true, heardSame = false))
        assertFalse(KoFileSameAsStream.carries(Clock.Unknown, Clock.YouTube, playsFile = false, heardSame = true))
        assertFalse(KoFileSameAsStream.carries(Clock.Unknown, Clock.Unknown, playsFile = true, heardSame = true))
    }

    @Test
    fun theAlignmentsMillisecondGrainIsStillIdentical() {
        // Espresso: `hq-verify: downloaded file download match=100% offset=1 -> accepted`.
        KoFileSameAsStream.measured(id, uri, "v1", fresh = true, ok = true, offsetMs = 1L, rate = 1.0)
        assertTrue(KoFileSameAsStream.heardSame(id, uri) { "v1" })
        assertTrue(KoFileSameAsStream.identical(-30L, 1.0))
        assertFalse(KoFileSameAsStream.identical(31L, 1.0))
        assertFalse(KoFileSameAsStream.identical(null, 1.0))
    }

    @Test
    fun theSiblingIsTriedForASubstituteClockOrAFileHeardToBeTheStream() {
        val alt = Clock.Substitute("tg", "42")
        // The old rule, unchanged: a substitute on either side maps a tagged answer.
        assertTrue(KoFileSameAsStream.siblingTried(alt, Clock.YouTube) { false })
        assertTrue(KoFileSameAsStream.siblingTried(Clock.YouTube, alt) { false })
        assertFalse(KoFileSameAsStream.siblingTried(Clock.YouTube, Clock.YouTube) { false })
        // An untagged answer was never tried; now only through the hearing.
        assertFalse(KoFileSameAsStream.siblingTried(Clock.Unknown, alt) { false })
        assertTrue(KoFileSameAsStream.siblingTried(Clock.Unknown, alt) { true })
        assertTrue(KoFileSameAsStream.siblingTried(Clock.Unknown, Clock.YouTube) { true })
    }

    @Test
    fun theSiblingCarryAndTheDownloadCheckUseIt() {
        val timing = File("src/main/java/com/music/bitchord/playback/smart/LyricsTiming.kt").readText()
        assertTrue(timing.contains("KoFileSameAsStream.siblingTried(aiClock, playing)"))
        assertTrue(timing.contains("KoFileSameAsStream.carries(context, videoId, audioSource, aiClock, playing)"))
        val health = File("src/main/java/com/music/bitchord/download/DownloadHealth.kt").readText()
        assertTrue(health.contains("KoFileSameAsStream.measured(id, meta.uri, version, freshlyAccepted,"))
    }
}
