package com.music.bitchord

import com.music.bitchord.data.FeatureHealthCounterKeys
import com.music.bitchord.data.FeatureHealthObserver
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.playback.NextPrepLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The next-song log must not report a disabled step as a failed one. */
class NextPrepLogTest {

    @Test
    fun aDisabledLyricsStepIsOffNotNone() {
        assertEquals("off", NextPrepLog.lyrics(source = null, attempted = false))
    }

    @Test
    fun anAttemptedMissIsNone() {
        assertEquals("none", NextPrepLog.lyrics(source = null, attempted = true))
    }

    @Test
    fun aFoundAnswerNamesItsSource() {
        assertEquals("lrclib", NextPrepLog.lyrics(source = LyricsSource.LRCLIB, attempted = true))
    }

    @Test
    fun syncDistinguishesOffFromSkipped() {
        assertEquals("off", NextPrepLog.sync(applied = false, attempted = false))
        assertEquals("skipped", NextPrepLog.sync(applied = false, attempted = true))
        assertEquals("applied", NextPrepLog.sync(applied = true, attempted = true))
    }

    /**
     * The scorecard reads the same line. A disabled path is `off`, so it is
     * neither a lyrics success nor a sync attempt — counting it as both made a
     * device with the next-prep lyrics switch off report a perfect lyrics rate
     * for work that never ran (the failure `NextPrepLog` was written to end).
     */
    @Test
    fun aDisabledPathIsNotScoredAsAnAnswer() {
        val counts = HashMap<String, Long>()
        val observer = FeatureHealthObserver { key, amount ->
            counts[key] = (counts[key] ?: 0L) + amount
        }
        observer.observe(
            "BitChord",
            "next-prep: A version=a lyrics=${NextPrepLog.lyrics(null, attempted = false)} " +
                "hq=none sync=${NextPrepLog.sync(applied = false, attempted = false)} " +
                "mix=none(off) in 10ms",
            1_700_000_000_000L,
        )

        assertEquals(1L, counts[FeatureHealthCounterKeys.D1_ATTEMPTS])
        assertNull(counts[FeatureHealthCounterKeys.D1_LYRICS])
        assertNull(counts[FeatureHealthCounterKeys.D1_SYNC_ATTEMPTS])
        assertNull(counts[FeatureHealthCounterKeys.D1_SYNC_SUCCESS])
        assertNull(counts[FeatureHealthCounterKeys.D1_SYNC_SKIP])
    }

    /** A real answer is still counted, so the fix above cannot silence the metric. */
    @Test
    fun aFoundAnswerIsStillCountedAsOne() {
        val counts = HashMap<String, Long>()
        val observer = FeatureHealthObserver { key, amount ->
            counts[key] = (counts[key] ?: 0L) + amount
        }
        observer.observe(
            "BitChord",
            "next-prep: B version=b lyrics=${NextPrepLog.lyrics(LyricsSource.LRCLIB, attempted = true)} " +
                "hq=none sync=skipped(no-match) mix=none(not-ready) in 20ms",
            1_700_000_000_000L,
        )

        assertEquals(1L, counts[FeatureHealthCounterKeys.D1_ATTEMPTS])
        assertEquals(1L, counts[FeatureHealthCounterKeys.D1_LYRICS])
        assertEquals(1L, counts[FeatureHealthCounterKeys.D1_SYNC_ATTEMPTS])
        assertNull(counts[FeatureHealthCounterKeys.D1_SYNC_SUCCESS])
        assertEquals(1L, counts[FeatureHealthCounterKeys.D1_SYNC_SKIP])
    }
}
