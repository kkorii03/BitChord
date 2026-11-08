package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-02 sync-g: two late-version lookups started the same (song, version) pair
 * a moment apart, and the measurement for a song that had already ended kept
 * taking the one decode slot. Only the current and prepared-next rows are
 * measured, and only one late measurement per pair runs at a time.
 */
class LyricsTimingMeasurementGateTest {

    @Test
    fun `the playing song and the prepared next are wanted`() {
        assertTrue(LyricsTiming.measurementWanted("playing", "playing", "next"))
        assertTrue(LyricsTiming.measurementWanted("next", "playing", "next"))
    }

    @Test
    fun `a song that has left the window is not wanted`() {
        assertFalse(LyricsTiming.measurementWanted("gone", "playing", "next"))
        assertFalse(LyricsTiming.measurementWanted("gone", null, null))
    }

    @Test
    fun `a second late measurement of the same pair waits for the first`() {
        val flights = LyricsTiming.MeasurementFlights()
        assertTrue(flights.begin("song\u0000original"))
        assertFalse(flights.begin("song\u0000original"))
        assertTrue(flights.begin("song\u0000other-original"))
    }

    @Test
    fun `the pair can be measured again once the first has finished`() {
        val flights = LyricsTiming.MeasurementFlights()
        assertTrue(flights.begin("song\u0000original"))
        flights.end("song\u0000original")
        assertTrue(flights.begin("song\u0000original"))
    }

    private fun answer(
        original: String?,
        partial: Boolean = false,
        noChange: Boolean = false,
        version: Int = LyricsTiming.AUTO_VERSION,
    ) = LyricsTiming.Entry(
        autoVersion = version,
        auto = if (noChange) null else LyricsTiming.SavedMap(listOf(0L, 4200L), 1.0, 1.0, 1.0, 90),
        autoComplete = !partial,
        autoNoChange = noChange,
        autoRecordPartial = partial,
        autoOriginal = original,
    )

    @Test
    fun `an answer measured against this original is reused`() {
        assertTrue(LyricsTiming.measurementAnswerFor(answer("authoritative"), "authoritative"))
    }

    @Test
    fun `an opening-only answer is still an answer for its pair`() {
        assertTrue(LyricsTiming.measurementAnswerFor(answer("authoritative", partial = true), "authoritative"))
    }

    @Test
    fun `a no-change finding is reused for its own original`() {
        assertTrue(LyricsTiming.measurementAnswerFor(answer("authoritative", noChange = true), "authoritative"))
    }

    @Test
    fun `an answer measured against another original is measured again`() {
        assertFalse(LyricsTiming.measurementAnswerFor(answer("guessed"), "authoritative"))
    }

    @Test
    fun `an answer with unknown provenance is measured again`() {
        assertFalse(LyricsTiming.measurementAnswerFor(answer(null), "authoritative"))
    }

    @Test
    fun `an answer from an older measurement version is not reused`() {
        assertFalse(
            LyricsTiming.measurementAnswerFor(
                answer("authoritative", version = LyricsTiming.AUTO_VERSION - 1),
                "authoritative",
            ),
        )
    }

    @Test
    fun `a stale read with no first sample restarts from zero`() {
        assertTrue(LyricsTiming.staleReadingNeedsRestart(70.0, AudioDecoder.FAILURE_NO_FIRST_SAMPLE, false))
    }

    /**
     * 11-07 video-timing: the reference rules changed after these rows were
     * recorded (an act's own upload, the video's runtime rule, the playing row
     * as the record), so a stored "no original recording found" is not a
     * verdict on the new search. The stored version (3) must stay below
     * SEARCH_VERSION or that whole round's fix never runs for them.
     */
    @Test
    fun `a row that found nothing under the previous search is searched once more`() {
        assertTrue(
            LyricsTiming.searchOutdated(
                LyricsTiming.Entry(
                    autoVersion = LyricsTiming.AUTO_VERSION,
                    auto = null,
                    autoComplete = true,
                    searchVersion = 3,
                ),
            ),
        )
    }

    @Test
    fun `a no-change verdict is never searched again`() {
        assertFalse(
            LyricsTiming.searchOutdated(
                LyricsTiming.Entry(
                    autoVersion = LyricsTiming.AUTO_VERSION,
                    auto = null,
                    autoComplete = true,
                    autoNoChange = true,
                    searchVersion = 3,
                ),
            ),
        )
    }

    @Test
    fun `a row with an answer keeps it`() {
        assertFalse(LyricsTiming.searchOutdated(answer("authoritative")))
    }

    @Test
    fun `another decode failure does not re-decode the prefix`() {
        assertFalse(LyricsTiming.staleReadingNeedsRestart(70.0, "codec-start", false))
    }

    @Test
    fun `a read from zero or a second restart does not start over`() {
        assertFalse(LyricsTiming.staleReadingNeedsRestart(0.0, AudioDecoder.FAILURE_NO_FIRST_SAMPLE, false))
        assertFalse(LyricsTiming.staleReadingNeedsRestart(70.0, AudioDecoder.FAILURE_NO_FIRST_SAMPLE, true))
    }

    /**
     * User phone 11-08: 27 "no-first-sample" failures in 3 h. Each was a pass
     * that seeked to where the last pass ran out of bytes with nothing new on
     * disk, scanned for 1-2 s, then decoded the whole prefix again from zero.
     */
    @Test
    fun `a pass that read to the end is not decoded again until bytes arrive`() {
        assertEquals(LyricsTiming.ReadPlan.KEEP, LyricsTiming.readPlan(2_800_000L, exhaustedAt = 2_800_000L, failedAt = null))
        assertEquals(LyricsTiming.ReadPlan.KEEP, LyricsTiming.readPlan(2_000_000L, exhaustedAt = 2_800_000L, failedAt = null))
        assertEquals(LyricsTiming.ReadPlan.DECODE, LyricsTiming.readPlan(3_200_000L, exhaustedAt = 2_800_000L, failedAt = null))
    }

    @Test
    fun `a copy that gave no sample waits for more bytes instead of retrying`() {
        assertEquals(LyricsTiming.ReadPlan.SKIP, LyricsTiming.readPlan(1_500_000L, exhaustedAt = null, failedAt = 1_500_000L))
        assertEquals(LyricsTiming.ReadPlan.DECODE, LyricsTiming.readPlan(1_900_000L, exhaustedAt = null, failedAt = 1_500_000L))
    }

    @Test
    fun `a first read or an unknown size decodes as before`() {
        assertEquals(LyricsTiming.ReadPlan.DECODE, LyricsTiming.readPlan(2_800_000L, exhaustedAt = null, failedAt = null))
        assertEquals(LyricsTiming.ReadPlan.DECODE, LyricsTiming.readPlan(-1L, exhaustedAt = 2_800_000L, failedAt = 2_800_000L))
    }
}
