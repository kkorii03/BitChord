package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricLineIndex
import com.music.bitchord.data.lyrics.withEstimatedWords
import com.music.bitchord.ko.ui.player.koShouldDrawLyricFrame
import com.music.bitchord.playback.playingSpeed
import com.music.bitchord.ui.player.LyricClock
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricClockTest {
    private val frameMs = 1000.0 / 60

    /**
     * A song playing at [rate] from [startMs], read every 500 ms. Each reading
     * is stamped when it is taken, and then reaches the frame loop up to
     * [maxDelayMs] later — the way a reading did when the main thread was busy
     * with the recomposition that reading itself had caused.
     */
    private class Playback(
        val startMs: Long = 44_500L,
        val rate: Double = 1.0,
        val maxDelayMs: Double = 60.0,
        val stamped: Boolean = true,
        seed: Int = 7,
    ) {
        private val random = Random(seed)
        private var nextPollMs = 0.0
        private val inFlight = ArrayDeque<Pair<Double, Pair<Long, Double>>>()
        var report: Long = startMs
            private set
        var sampledAt: Double = if (stamped) 0.0 else Double.NaN
            private set

        fun trueAt(nowMs: Double) = startMs + nowMs * rate

        /** Advances deliveries to [nowMs]; afterwards [report] and [sampledAt] are what the frame sees. */
        fun deliver(nowMs: Double) {
            while (nextPollMs <= nowMs) {
                val reading = trueAt(nextPollMs).toLong()
                val arrives = nextPollMs + random.nextDouble() * maxDelayMs
                inFlight.addLast(arrives to (reading to nextPollMs))
                nextPollMs += 500.0
            }
            while (inFlight.isNotEmpty() && inFlight.first().first <= nowMs) {
                val (reading, at) = inFlight.removeFirst().second
                report = reading
                if (stamped) sampledAt = at
            }
        }
    }

    private class Run(val frames: List<Double>, val shown: List<Long>, val truth: List<Double>)

    /** Frames at 60 Hz, with a stall of up to [maxStallMs] — no frames at all — now and then. */
    private fun run(playback: Playback, seconds: Double, maxStallMs: Double = 0.0, seed: Int = 11): Run {
        val random = Random(seed)
        val clock = LyricClock(playback.startMs)
        val frames = ArrayList<Double>()
        val shown = ArrayList<Long>()
        val truth = ArrayList<Double>()
        var now = 0.0
        while (now <= seconds * 1000) {
            playback.deliver(now)
            frames += now
            shown += clock.frame(now, playback.report, playback.sampledAt, discontinuity = 0)
            truth += playback.trueAt(now)
            now += frameMs
            if (maxStallMs > 0 && random.nextDouble() < 0.02) now += random.nextDouble() * maxStallMs
        }
        return Run(frames, shown, truth)
    }

    private fun assertNeverBack(run: Run) {
        for (i in 1 until run.shown.size) {
            assertTrue("went back at ${run.frames[i]}", run.shown[i] >= run.shown[i - 1])
        }
    }

    /** The bug: readings held up behind a busy main thread rolled the words back. */
    /**
     * 11-07 phone on Bluetooth, probes after opening the panel: a reading 130-170 ms off
     * the song, rate 0.915-1.118 for the next seconds, words slowing and rushing.
     */
    @Test fun strayReadingsDoNotChangeThePaceAfterOpeningThePanel() {
        val strays = longArrayOf(0, -130, 30, 90, -160, 40, 120, -70, 20, -140, 60, 100, -30, 50, -90, 10, 130, -60, 0, 80)
        val clock = LyricClock(44_500L)
        var now = 0.0
        var worst = 0.0
        var reading = -1
        while (now < 10_000.0) {
            val at = (now / 500.0).toInt()
            if (at != reading) reading = at
            val taken = at * 500.0
            val report = (44_500.0 + taken).toLong() + strays[at % strays.size]
            clock.frame(now, report, taken, 0L)
            worst = maxOf(worst, abs(clock.rate - 1.0))
            now += frameMs
        }
        assertTrue("rate strayed by $worst", worst <= 0.08)
    }

    @Test fun strayReadingsDoNotMakeTheWordsRushOrDrag() {
        val strays = longArrayOf(0, -130, 30, 90, -160, 40, 120, -70, 20, -140, 60, 100, -30, 50, -90, 10, 130, -60, 0, 80)
        val clock = LyricClock(44_500L)
        var now = 0.0
        var last = Double.NaN
        var fastest = 1.0
        var slowest = 1.0
        var at = 0L
        while (now < 12_000.0) {
            val reading = (now / 500.0).toInt()
            val taken = reading * 500.0
            val drawn = clock.frame(now, (44_500.0 + taken).toLong() + strays[reading % strays.size], taken, 0L)
            // Pace over a quarter second, once the first reading has settled in.
            if (((now / 250.0).toInt()).toLong() != at) {
                at = (now / 250.0).toInt().toLong()
                if (!last.isNaN() && now > 1_000.0) {
                    val pace = (drawn - last) / 250.0
                    fastest = maxOf(fastest, pace)
                    slowest = minOf(slowest, pace)
                }
                last = drawn.toDouble()
            }
            now += frameMs
        }
        assertTrue("fastest $fastest slowest $slowest", fastest <= 1.12 && slowest >= 0.88)
    }

    /**
     * The same stray readings at another speed. At 1x the clock damps them; at
     * 1.25x and 0.75x it used to follow every one, and the words ran 25 % fast
     * or slow after each. The rate is measured from the raw readings, so the
     * damping costs it nothing; only the first seconds, while the rate is still
     * being learned, are left out of the measurement.
     */
    @Test fun strayReadingsDoNotMakeTheWordsRushOrDragAtOtherSpeeds() {
        val strays = longArrayOf(0, -130, 30, 90, -160, 40, 120, -70, 20, -140, 60, 100, -30, 50, -90, 10, 130, -60, 0, 80)
        fun pace(rate: Double): Pair<Double, Double> {
            val clock = LyricClock(44_500L)
            var now = 0.0
            var last = Double.NaN
            var fastest = 1.0
            var slowest = 1.0
            var at = 0L
            while (now < 20_000.0) {
                val reading = (now / 500.0).toInt()
                val taken = reading * 500.0
                val drawn = clock.frame(now, (44_500.0 + taken * rate).toLong() + strays[reading % strays.size], taken, 0L)
                if (((now / 250.0).toInt()).toLong() != at) {
                    at = (now / 250.0).toInt().toLong()
                    if (!last.isNaN() && now > 4_000.0) {
                        val measured = (drawn - last) / 250.0 / rate
                        fastest = maxOf(fastest, measured)
                        slowest = minOf(slowest, measured)
                    }
                    last = drawn.toDouble()
                }
                now += frameMs
            }
            assertEquals(rate, clock.rate, 0.05)
            return fastest to slowest
        }
        val normal = pace(1.0)
        for (rate in doubleArrayOf(1.25, 0.75)) {
            val (fastest, slowest) = pace(rate)
            assertTrue(
                "$rate x: fastest $fastest slowest $slowest (1x: $normal)",
                fastest <= 1.12 && slowest >= 0.88,
            )
        }
    }

    @Test fun aRealGapStillClosesWithinSeconds() {
        val clock = LyricClock(10_000L)
        var now = 0.0
        while (now < 8_000.0) {
            val taken = (now / 500.0).toInt() * 500.0
            // The song runs 200 ms ahead of where the clock started.
            clock.frame(now, (10_200.0 + taken).toLong(), taken, 0L)
            now += frameMs
        }
        assertEquals(10_200.0 + now, clock.displayedMs, 40.0)
    }

    private class SteadyRun(val atMs: List<Double>, val offMs: List<Double>, val paces: List<Double>, val shown: List<Long>) {
        fun medianOff(fromMs: Double = 3_000.0, untilMs: Double = Double.MAX_VALUE): Double =
            atMs.indices.filter { atMs[it] in fromMs..untilMs }.map { abs(offMs[it]) }.sorted().let { it[it.size / 2] }

        fun worstOff(fromMs: Double = 3_000.0, untilMs: Double = Double.MAX_VALUE): Double =
            atMs.indices.filter { atMs[it] in fromMs..untilMs }.maxOf { abs(offMs[it]) }

        fun shareOfPaces(test: (Double) -> Boolean): Double = paces.count(test).toDouble() / paces.size
    }

    /**
     * Sixty frames a second against a song at [songMs], read every half second. [stray] is how
     * far each reading is off the song. [speed] is what the clock is told; NaN tells it nothing.
     */
    private fun steadyRun(
        seconds: Double = 120.0,
        speed: (Double) -> Double = { 1.0 },
        songMs: (Double) -> Double = { 5_000.0 + it },
        stray: (java.util.Random, Double) -> Double,
    ): SteadyRun {
        val random = java.util.Random(3)
        val clock = LyricClock(songMs(0.0).toLong())
        val at = ArrayList<Double>()
        val off = ArrayList<Double>()
        val paces = ArrayList<Double>()
        val shown = ArrayList<Long>()
        var now = 0.0
        var nextReading = 0.0
        var reading = 0L
        var taken = 0.0
        var paceFrom = songMs(0.0)
        var paceFromAt = 0.0
        while (now < seconds * 1000) {
            if (now >= nextReading) {
                reading = (songMs(now) + stray(random, now)).toLong()
                taken = now
                nextReading = now + 500.0 + (random.nextDouble() - 0.5) * 40.0
            }
            val drawn = clock.frame(now, reading, taken, 0L, speed(now))
            at += now
            off += drawn - songMs(now)
            shown += drawn
            if (now - paceFromAt >= 250.0) {
                if (now > 3_000.0) paces += (drawn - paceFrom) / (now - paceFromAt)
                paceFrom = drawn.toDouble()
                paceFromAt = now
            }
            now += frameMs
        }
        return SteadyRun(at, off, paces, shown)
    }

    /** Readings as on the phone: sliding back at [sag] of real time, back on the song every 3 s. */
    private fun sagging(sag: Double = 0.35, startsAtMs: Double = 0.0): (java.util.Random, Double) -> Double =
        { random, now -> -(((now + startsAtMs) % 3_030.0) * sag) + random.nextGaussian() * 25.0 }

    /**
     * 11-07, Rap God on LDAC Bluetooth, from the listener's screen recording: the sheet was right
     * to 0.1 s, yet the words ran at 0.54-0.71x and rushed every 3 s, 1.25 s behind at worst.
     */
    @Test fun saggingReadingsDoNotSlowTheWordsWhenTheSpeedIsKnown() {
        val run = steadyRun(stray = sagging())
        assertTrue("median ${run.medianOff()}", run.medianOff() <= 120.0)
        assertTrue("worst ${run.worstOff()}", run.worstOff() <= 300.0)
        assertTrue("slow ${run.shareOfPaces { it < 0.85 }}", run.shareOfPaces { it < 0.85 } <= 0.01)
        assertTrue("rushed ${run.shareOfPaces { it > 1.25 }}", run.shareOfPaces { it > 1.25 } <= 0.01)
    }

    /** The control: these same readings do break a clock that learns its rate from them. */
    @Test fun saggingReadingsAreWhatBrokeTheClock() {
        val run = steadyRun(speed = { Double.NaN }, stray = sagging())
        assertTrue("median ${run.medianOff()}", run.medianOff() >= 400.0)
        assertTrue("slow ${run.shareOfPaces { it < 0.85 }}", run.shareOfPaces { it < 0.85 } >= 0.5)
    }

    @Test fun aRunThatStartsOnASaggedReadingCatchesUpWithoutRushing() {
        val run = steadyRun(stray = sagging(startsAtMs = 2_000.0))
        assertTrue("worst later ${run.worstOff(fromMs = 20_000.0)}", run.worstOff(fromMs = 20_000.0) <= 300.0)
        assertTrue("rushed ${run.shareOfPaces { it > 1.3 }}", run.shareOfPaces { it > 1.3 } <= 0.01)
    }

    @Test fun ordinaryNoiseStaysAsCloseAsBeforeAndNeverGoesBack() {
        val run = steadyRun(stray = { random, _ -> random.nextGaussian() * 100.0 })
        assertTrue("median ${run.medianOff()}", run.medianOff() <= 60.0)
        assertTrue("worst ${run.worstOff()}", run.worstOff() <= 250.0)
        for (i in 1 until run.shown.size) assertTrue("went back at ${run.atMs[i]}", run.shown[i] >= run.shown[i - 1])
    }

    @Test fun anUnannouncedSlipAheadIsFollowedWithinTwoSeconds() {
        val run = steadyRun(seconds = 40.0, songMs = { 5_000.0 + it + if (it >= 20_000.0) 1_500.0 else 0.0 }, stray = { _, _ -> 0.0 })
        assertTrue("worst after ${run.worstOff(fromMs = 22_000.0)}", run.worstOff(fromMs = 22_000.0) <= 100.0)
    }

    @Test fun aSmallLastingGapClosesWithinSeconds() {
        val run = steadyRun(seconds = 40.0, songMs = { 5_000.0 + it + if (it >= 20_000.0) 500.0 else 0.0 }, stray = { _, _ -> 0.0 })
        assertTrue("worst after ${run.worstOff(fromMs = 28_000.0)}", run.worstOff(fromMs = 28_000.0) <= 100.0)
    }

    // A slip behind closes at the unseen steer only (5%: 1.5 s in about 30 s), see the next test.
    @Test fun anUnannouncedSlipBehindClosesWithoutGoingBack() {
        val run = steadyRun(seconds = 80.0, songMs = { 5_000.0 + it - if (it >= 20_000.0) 1_500.0 else 0.0 }, stray = { _, _ -> 0.0 })
        assertTrue("worst after ${run.worstOff(fromMs = 60_000.0)}", run.worstOff(fromMs = 60_000.0) <= 100.0)
        assertTrue("slow ${run.shareOfPaces { it < 0.9 }}", run.shareOfPaces { it < 0.9 } <= 0.01)
        for (i in 1 until run.shown.size) assertTrue("went back at ${run.atMs[i]}", run.shown[i] >= run.shown[i - 1])
    }

    /**
     * 11-08, Rap God on LDAC, vc173 log: the readings fell about 950 ms behind for ten seconds and
     * came back, while the recorded sound never paused. The words went to 0.9x for seven seconds
     * and then 1.4x to catch up.
     */
    @Test fun readingsThatDropBehindForSecondsDoNotSlowTheWords() {
        val run = steadyRun(
            seconds = 60.0,
            stray = { random, now -> (if (now >= 20_000.0 && now < 30_000.0) -950.0 else 0.0) + random.nextGaussian() * 25.0 },
        )
        assertTrue("slow ${run.shareOfPaces { it < 0.9 }}", run.shareOfPaces { it < 0.9 } <= 0.01)
        assertTrue("rushed ${run.shareOfPaces { it > 1.3 }}", run.shareOfPaces { it > 1.3 } <= 0.01)
        assertTrue("worst ${run.worstOff()}", run.worstOff() <= 600.0)
    }

    @Test fun anAnnouncedSeekStillMovesTheWordsBackAtOnce() {
        val clock = LyricClock(60_000L)
        var now = 0.0
        while (now < 5_000.0) {
            val taken = (now / 500.0).toInt() * 500.0
            clock.frame(now, (60_000.0 + taken).toLong(), taken, 0L, 1.0)
            now += frameMs
        }
        val seekAt = now
        while (now < seekAt + 400.0) {
            val taken = maxOf(seekAt, seekAt + ((now - seekAt) / 500.0).toInt() * 500.0)
            clock.frame(now, (10_000.0 + (taken - seekAt)).toLong(), taken, 1L, 1.0)
            now += frameMs
        }
        assertEquals(10_000.0 + (now - seekAt), clock.displayedMs, 50.0)
    }

    @Test fun theWordsRunAtTheSpeedTheyAreTold() {
        val run = steadyRun(speed = { 1.25 }, songMs = { 5_000.0 + it * 1.25 }, stray = { random, _ -> random.nextGaussian() * 40.0 })
        assertTrue("median ${run.medianOff()}", run.medianOff() <= 60.0)
        assertEquals(1.25, run.paces.sorted()[run.paces.size / 2], 0.05)
    }

    /** Automix eases a stretched song back to normal in steps; the real speed follows it, the setting does not. */
    @Test fun aTempoEasingBackIsFollowed() {
        val speed = { now: Double -> if (now < 6_000.0) 1.1 else 1.0 }
        val song = { now: Double -> 5_000.0 + minOf(now, 6_000.0) * 1.1 + maxOf(now - 6_000.0, 0.0) }
        val run = steadyRun(seconds = 30.0, speed = speed, songMs = song, stray = { random, _ -> random.nextGaussian() * 40.0 })
        assertTrue("worst ${run.worstOff()}", run.worstOff() <= 150.0)
    }

    @Test fun aWildSpeedFigureIsNotTakenOnTrust() {
        assertEquals(1.0f, playingSpeed(1.0f, 1.0f), 0.0001f)
        assertEquals(1.1f, playingSpeed(1.1f, 1.0f), 0.0001f)
        assertEquals(0.85f, playingSpeed(0.65f, 1.0f), 0.0001f)
        assertEquals(1.5f, playingSpeed(0f, 1.5f), 0.0001f)
    }

    @Test fun aRealChangeOfSpeedStillShowsWithinTwoSeconds() {
        val clock = LyricClock(0L)
        var now = 0.0
        while (now < 2_000.0) {
            val taken = (now / 500.0).toInt() * 500.0
            clock.frame(now, (taken * 1.25).toLong(), taken, 0L)
            now += frameMs
        }
        assertEquals(1.25, clock.rate, 0.05)
    }

    @Test fun lateReadingsNeverMoveTheWordsBack() {
        val run = run(Playback(maxDelayMs = 800.0), seconds = 90.0, maxStallMs = 600.0)
        assertNeverBack(run)
    }

    @Test fun lateReadingsDoNotPullTheWordsOffTheSong() {
        val run = run(Playback(maxDelayMs = 800.0), seconds = 60.0, maxStallMs = 600.0)
        for (i in run.frames.indices) {
            if (run.frames[i] < 2_000) continue
            val error = run.shown[i] - run.truth[i]
            assertTrue("off by $error at ${run.frames[i]}", abs(error) < 40.0)
        }
    }

    /** The earlier bug: a `maxOf` ratchet froze the words for up to a second. */
    @Test fun lateReadingsNeverStallTheWords() {
        val run = run(Playback(maxDelayMs = 800.0), seconds = 60.0)
        val window = 15
        for (i in window until run.shown.size) {
            val moved = run.shown[i] - run.shown[i - window]
            val elapsed = run.frames[i] - run.frames[i - window]
            assertTrue("stalled near ${run.frames[i]}: $moved ms in $elapsed", moved >= elapsed * 0.5)
        }
    }

    /** A source that cannot stamp its readings still never goes backwards. */
    @Test fun unstampedReadingsNeverMoveTheWordsBack() {
        val run = run(Playback(maxDelayMs = 800.0, stamped = false), seconds = 60.0, maxStallMs = 600.0)
        assertNeverBack(run)
    }

    @Test fun learnsAFasterPlaybackRate() {
        val run = run(Playback(rate = 1.25, maxDelayMs = 300.0), seconds = 30.0)
        for (i in run.frames.indices) {
            if (run.frames[i] < 4_000) continue
            val error = run.shown[i] - run.truth[i]
            assertTrue("off by $error at ${run.frames[i]}", abs(error) < 40.0)
        }
        assertNeverBack(run)
    }

    /** Drives a clock on a song at 1x from 10 s, with [readingAt] deciding what each 500 ms reading says. */
    private fun steady(
        until: Double,
        clock: LyricClock = LyricClock(10_000),
        discontinuity: (Double) -> Long = { 0L },
        readingAt: (Double) -> Long = { 10_000 + it.toLong() },
        onFrame: (now: Double, shown: Long) -> Unit = { _, _ -> },
    ): Long {
        var now = 0.0
        var shown = 0L
        while (now <= until) {
            val taken = (now / 500).toLong() * 500.0
            shown = clock.frame(now, readingAt(taken), taken, discontinuity(now))
            onFrame(now, shown)
            now += frameMs
        }
        return shown
    }

    @Test fun oneWrongReadingIsIgnored() {
        var previous = Long.MIN_VALUE
        val last = steady(until = 8_000.0, readingAt = { taken ->
            10_000 + taken.toLong() + when (taken) {
                3_000.0 -> 1_500
                5_000.0 -> -1_500
                else -> 0
            }
        }) { now, shown ->
            assertTrue("went back at $now", shown >= previous)
            if (now > 1_000) assertTrue("followed the bad reading at $now", abs(shown - (10_000 + now)) < 40)
            previous = shown
        }
        assertTrue(abs(last - 18_000) < 40)
    }

    /** Tapping the line before this one: an announced jump, rolled back to rather than cut. */
    @Test fun announcedBackwardSeekGlidesBackAndLands() {
        var shown = 0L
        var largestStep = 0L
        val seekAt = 4_000.0
        steady(
            until = 5_000.0,
            discontinuity = { if (it >= seekAt) 1L else 0L },
            readingAt = { taken -> 10_000 + taken.toLong() - if (taken >= seekAt) 1_000 else 0 },
        ) { now, next ->
            if (now > seekAt) largestStep = maxOf(largestStep, abs(next - shown))
            shown = next
        }
        assertTrue("cut rather than glided: $largestStep ms in a frame", largestStep < 150)
        assertTrue("never landed: $shown", abs(shown - 14_000) < 40)
    }

    @Test fun announcedFarSeekJumpsAtOnce() {
        val clock = LyricClock(30_000)
        clock.frame(0.0, 30_000, 0.0, 0)
        clock.frame(16.0, 30_000, 0.0, 0)
        assertEquals(4_003L, clock.frame(33.0, 4_000, 30.0, 1))
    }

    /** The song really moved back without saying so: the words slow down to meet it, never reverse. */
    @Test fun unannouncedSmallRewindSlowsDownInsteadOfReversing() {
        var previous = Long.MIN_VALUE
        val last = steady(
            until = 12_000.0,
            readingAt = { taken -> 10_000 + taken.toLong() - if (taken >= 4_000) 800 else 0 },
        ) { now, shown ->
            assertTrue("went back at $now", shown >= previous)
            previous = shown
        }
        assertTrue("never met the song: $last", abs(last - (22_000 - 800)) < 40)
    }

    @Test fun unannouncedFarJumpIsFollowed() {
        val last = steady(
            until = 8_000.0,
            readingAt = { taken -> 10_000 + taken.toLong() - if (taken >= 4_000) 20_000 else 0 },
        )
        assertTrue("stayed lost: $last", abs(last - (18_000 - 20_000)) < 40)
    }

    @Test fun unannouncedForwardJumpCatchesUp() {
        var previous = Long.MIN_VALUE
        val last = steady(
            until = 8_000.0,
            readingAt = { taken -> 10_000 + taken.toLong() + if (taken >= 4_000) 1_000 else 0 },
        ) { now, shown ->
            assertTrue("went back at $now", shown >= previous)
            previous = shown
        }
        assertTrue(abs(last - 19_000) < 40)
    }

    @Test fun holdSettlesAtOnce() {
        val clock = LyricClock(10_000)
        clock.frame(0.0, 10_000, 0.0, 0)
        clock.frame(400.0, 10_400, 400.0, 0)
        clock.hold(10_380, 420.0)
        assertEquals(10_380.0, clock.displayedMs, 0.0)
    }

    /** A reading taken before a pause is where the song still is when it resumes, however long ago. */
    @Test fun resumingAfterAPauseStartsWhereItStopped() {
        val clock = LyricClock(10_000)
        clock.frame(0.0, 10_000, 0.0, 0)
        clock.hold(10_000, 5.0)
        clock.restart()
        assertEquals(10_000L, clock.frame(60_000.0, 10_000, 0.0, 0))
        assertEquals(10_016L, clock.frame(60_016.0, 10_000, 0.0, 0))
    }

    /** Coming back to the app while it played on: the last reading has moved on by its age. */
    @Test fun returningToTheAppExtrapolatesFromTheLastReading() {
        val clock = LyricClock(10_000)
        clock.frame(0.0, 10_000, 0.0, 0)
        clock.restart()
        assertEquals(40_000L, clock.frame(30_000.0, 10_000, 0.0, 0))
    }

    /**
     * A panel not on show follows the readings while the song plays on, and opens between two of
     * them. The last reading is up to half a second old on its first frame: taken for the moment
     * playback stood still, it started the words that far behind the song, and they stayed there
     * until a later reading made them rush (11-06, "stops, then rushes to catch up").
     */
    @Test fun openingThePanelMidSongStartsOnTheSong() {
        val clock = LyricClock(10_000)
        var taken = 0.0
        while (taken <= 4_000.0) {
            clock.hold(10_000 + taken.toLong(), taken + 5.0, songStopped = false)
            taken += 500.0
        }
        val lastTaken = taken - 500.0
        val lastReading = 10_000 + lastTaken.toLong()
        clock.restart()
        var previous = Long.MIN_VALUE
        var now = lastTaken + 480.0
        while (now <= lastTaken + 3_000.0) {
            val newest = (now / 500).toLong() * 500.0
            val reading = if (newest <= lastTaken) lastReading else 10_000 + newest.toLong()
            val shown = clock.frame(now, reading, minOf(newest, now).coerceAtLeast(lastTaken), 0)
            assertTrue("went back at $now", shown >= previous)
            assertTrue("${10_000 + now - shown} ms behind the song at $now", abs(10_000 + now - shown) < 40.0)
            previous = shown
            now += frameMs
        }
    }

    /** A pause earlier in the song says nothing about readings taken since: they still carry forward. */
    @Test fun aPauseEarlierInTheSongDoesNotStopLaterReadingsCarrying() {
        val clock = LyricClock(10_000)
        clock.hold(10_000, 5.0)
        clock.restart()
        clock.frame(100.0, 10_000, 5.0, 0)
        clock.restart()
        assertEquals(11_380L, clock.frame(1_480.0, 10_900, 1_000.0, 0))
    }

    /** Moving the lyrics offset shifts every reading at once; announced, it is a jump and not a fault. */
    @Test fun movingTheOffsetIsFollowed() {
        val last = steady(
            until = 5_000.0,
            discontinuity = { if (it >= 3_000) 1L else 0L },
            readingAt = { taken -> 10_000 + taken.toLong() - if (taken >= 3_000) 1_500 else 0 },
        )
        assertTrue(abs(last - (15_000 - 1_500)) < 40)
    }

    /** One line of a line-synced sheet, as the panel draws it: words guessed over the line's own slot. */
    private fun sheetLine(at: Long, next: Long?): LyricLine =
        LyricLine(timeMs = at, text = "사랑해 너를 정말 오늘도").withEstimatedWords(next)

    private class Sweep(
        val maxLagMs: Double,
        val maxIndexLateMs: Double,
        val handoverChars: Float,
        val maxStepChars: Float,
        val freezeMs: Long,
    )

    /**
     * Drives the whole drawn picture — the real clock, the real line index and
     * the real guessed sweep — over a song that runs at a steady 1x, with a
     * line every four seconds and readings dated when they were taken.
     *
     * Reports how far behind the song the words ever get, how late the line
     * changes, how much of a line is already lit on the frame it takes over,
     * the largest jump the sweep makes between two frames of one line, and how
     * long each line sits frozen at its end.
     */
    private fun sweep(seconds: Double, hz: Double, maxDelayMs: Double = 60.0, seed: Int = 3): Sweep {
        val stamps = (0 until 40).map { 2_000L + it * 4_000L }
        val lines = stamps.mapIndexed { i, at -> sheetLine(at, stamps.getOrNull(i + 1)) }
        val index = LyricLineIndex(lines)
        val clock = LyricClock(stamps.first())
        val random = Random(seed)
        val frameMs = 1_000.0 / hz
        var now = 0.0
        var lastDrawnNanos = 0L
        var nextPollMs = 0.0
        val inFlight = ArrayDeque<Pair<Double, Pair<Long, Double>>>()
        var report = stamps.first()
        var sampledAt = 0.0
        var maxLag = 0.0
        var maxIndexLate = 0.0
        var handoverChars = 0f
        var maxStep = 0f
        var lastLine = Int.MIN_VALUE
        var lastRevealed = 0f
        while (now <= seconds * 1_000) {
            while (nextPollMs <= now) {
                inFlight.addLast(
                    (nextPollMs + random.nextDouble() * maxDelayMs) to
                        ((2_000.0 + nextPollMs).toLong() to nextPollMs),
                )
                nextPollMs += 500.0
            }
            while (inFlight.isNotEmpty() && inFlight.first().first <= now) {
                val (reading, at) = inFlight.removeFirst().second
                report = reading
                sampledAt = at
            }
            val nowNanos = (now * 1_000_000.0).toLong()
            if (!koShouldDrawLyricFrame(nowNanos, lastDrawnNanos)) {
                lastDrawnNanos = nowNanos
                val shown = clock.frame(now, report, sampledAt, discontinuity = 0)
                val truth = 2_000.0 + now
                val lag = abs(truth - shown)
                if (now > 3_000 && lag > maxLag) maxLag = lag
                val want = index.lineAt(truth.toLong())
                val got = index.lineAt(shown)
                if (got < want) {
                    val late = truth - lines[want].timeMs
                    if (late > maxIndexLate) maxIndexLate = late
                }
                val revealed = lines.getOrNull(got)?.revealedChars(shown) ?: 0f
                if (got != lastLine) {
                    if (lastLine != Int.MIN_VALUE && got == lastLine + 1 && revealed > handoverChars) {
                        handoverChars = revealed
                    }
                    lastRevealed = revealed
                    lastLine = got
                } else {
                    val step = revealed - lastRevealed
                    if (step > maxStep) maxStep = step
                    lastRevealed = revealed
                }
            }
            now += frameMs
        }
        val freeze = lines.zipWithNext { line, next -> next.timeMs - line.words.last().endMs }.maxOrNull() ?: 0L
        return Sweep(maxLag, maxIndexLate, handoverChars, maxStep, freeze)
    }

    /**
     * The report from the phone: the sweep stops at the end of a line and then
     * races through the first words of the next one. If the clock is what
     * stalls, a song that runs steadily has to show it.
     *
     * Nothing here is allowed to lag: at 60 Hz and at 120 Hz (where the frame
     * gate halves the frames the clock is given), the words stay within a few
     * tens of milliseconds of the song, the line changes when its stamp passes,
     * the new line opens unsung, and no frame lights more than a character.
     */
    @Test fun steadySongIsNeverLeftBehindAtALineBoundary() {
        for (hz in doubleArrayOf(60.0, 120.0)) {
            val run = sweep(seconds = 120.0, hz = hz)
            println(
                "sweep ${hz.toInt()}Hz: maxLag=${run.maxLagMs}ms indexLate=${run.maxIndexLateMs}ms " +
                    "handover=${run.handoverChars}chars maxStep=${run.maxStepChars}chars " +
                    "freezeAtLineEnd=${run.freezeMs}ms",
            )
            assertTrue("$hz Hz: the words sat ${run.maxLagMs} ms behind the song", run.maxLagMs < 60.0)
            assertTrue("$hz Hz: the line changed ${run.maxIndexLateMs} ms late", run.maxIndexLateMs < 60.0)
            assertTrue(
                "$hz Hz: the new line opened with ${run.handoverChars} characters already lit",
                run.handoverChars < 1.5f,
            )
            assertTrue("$hz Hz: the sweep jumped ${run.maxStepChars} characters in one frame", run.maxStepChars < 1.5f)
        }
    }
}
