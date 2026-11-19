package com.music.bitchord.ko.playback

/**
 * [KO] After a resume, the playhead cannot be further on than the audio could have played.
 *
 * 11-09, S24 on LDAC Bluetooth (JBL Tour Pro 3), vc192: the lyrics ran wrong after the app came
 * back from a pause and fixed themselves on a seek. A seek gives Media3 a fresh AudioTrack (every
 * seek logs `AudioTrack ... reason=media3-reconfigure`); a pause and a resume keep the old one.
 * At 20:36:34 the player's own reading went +3.47 s past "where it stopped + time since play" in
 * the first 1.5 s after a resume, with nothing announced (log `drawn=180511 reading=183967`), and
 * stayed there; the listener pressed MARK seconds later. Media3 1.11 takes a timestamp up to 5 s
 * off the play head as plausible and does not smooth a step of 1 s or more, so a bad position on
 * a resumed track goes straight to the player and lasts until the track is replaced.
 *
 * Whatever the cause inside the platform, this much is physical: from the moment the play head
 * starts moving again, the song can have advanced by at most the time since then times the speed.
 * The sink's reading is held to that line, plus [SLACK_US]; one at or below it passes untouched,
 * so a correct resume (which starts late, never early) and a reading that sags are not changed.
 *
 * Where the song stopped is taken as the top of the recent readings, carried forward: on this
 * route the readings sag by ~0.93 s and climb back over ~25 s (11-09 20:34:33 and 20:35:00), the
 * words held steady through both, and a pause in the middle of a sag must not hold the song back
 * at the bottom of it.
 *
 * Plain arithmetic, called by the sink on its own position polls: no timer, no thread.
 */
class KoResumeCeiling(private val log: (String) -> Unit = {}) {
    // The top of the recent readings, carried at the playing speed (see the class comment).
    private var lineUs = UNSET
    private var lineNanos = 0L
    private var lastSpeed = 1.0

    // Where the song stopped, and when; set by [pause], used by the next [play].
    private var baseUs = UNSET
    private var stopped = false
    private var stoppedNanos = 0L

    // The run since the last resume.
    private var armed = false
    private var waitingForMove = false
    private var firstRawUs = UNSET
    private var ceilingUs = 0L
    private var ceilingNanos = 0L
    private var movedNanos = 0L
    private var pauseMs = 0L
    private var peakAheadUs = Long.MIN_VALUE
    private var holdUs = 0L
    private var stepLogged = false
    private var summaryDone = false

    /** How far the last answer was held back, µs; 0 when it passed untouched. */
    val heldBackUs: Long get() = holdUs

    /** The sink stopped (pause, or a stall): remember where the song is. */
    fun pause(nowNanos: Long) {
        finishRun(nowNanos, "paused")
        armed = false
        holdUs = 0L // a hold ends with its run; the next resume is judged on its own
        if (stopped) return
        stopped = true
        stoppedNanos = nowNanos
        if (lineUs != UNSET) baseUs = lineUs
    }

    /** The sink started. Only a start after a [pause], with no [clear] between, is a resume. */
    fun play(nowNanos: Long) {
        stopped = false
        lineNanos = nowNanos
        val base = baseUs
        baseUs = UNSET
        if (base == UNSET) return
        armed = true
        waitingForMove = true
        firstRawUs = UNSET
        ceilingUs = base
        ceilingNanos = nowNanos
        movedNanos = nowNanos
        pauseMs = (nowNanos - stoppedNanos) / 1_000_000L
        peakAheadUs = Long.MIN_VALUE
        holdUs = 0L
        stepLogged = false
        summaryDone = false
    }

    /** A flush, a reset, a stream change: the positions start over, and Media3 with them. */
    fun clear(why: String) {
        if (armed && holdUs >= STEP_REPORT_US) {
            log("resume-clock: released a ${holdUs / 1000}ms hold ($why)")
        }
        armed = false
        baseUs = UNSET
        lineUs = UNSET
        holdUs = 0L
    }

    /**
     * The position to report for the sink's reading [rawUs] at [nowNanos], playing at [speed].
     * [allowed] false is a stretch where the position legitimately outruns the clock (silence
     * skipping, an Automix loop splice): passed through, and nothing learned from it.
     * [drained] true is a sink that has played every frame written to it.
     */
    fun position(rawUs: Long, nowNanos: Long, speed: Float, allowed: Boolean = true, drained: Boolean = false): Long {
        if (!allowed) {
            if (armed) clear("not tracked")
            lineUs = UNSET
            return rawUs
        }
        // [KO] midsilence (11-19 15:47, Centuries 24/96 FLAC on cellular, JBL Tour Pro 3): a hold
        // says the sound is still that far behind the reading, so the sink must still have it to
        // play. Once the sink has played everything, the hold is wrong, and Media3 reads it as
        // audio in hand: `pos=107553 buffered=110976 loading=false` = the 3441 ms hold, its
        // "< 0.5 s left: drop the back buffer" rescue never ran and the track went silent
        // until a stuck-player error. Released, and not carried into the rebuffer's resume.
        if (drained && armed) {
            clear("sink drained")
            return rawUs
        }
        val rate = if (speed.isFinite() && speed > 0f) speed.toDouble().coerceIn(MIN_SPEED, MAX_SPEED) else lastSpeed
        lastSpeed = rate
        val out = if (armed) held(rawUs, nowNanos, rate) else rawUs
        // [KO] RG4-1: the line follows what is reported, so a reading held back here is where the
        // next resume starts from: on the route this was built for the reading stays ahead until a
        // seek (11-09), and a second pause must not let it through. What stopped the pile-up of
        // 11-12 (-3.9, -7.7, -11.4, -15.2 s over five pauses in 30 s) is [MAX_HOLD_US] in [held]:
        // Media3 caps the reading at the frames written, one AudioTrack buffer (~4 s) ahead of the
        // sound at most, so no hold may be larger.
        // It is not carried while the song stands still.
        val moving = !stopped && !(armed && waitingForMove)
        lineUs = when {
            lineUs == UNSET -> out
            moving -> maxOf(out, minOf(lineUs + scaled(nowNanos - lineNanos, rate), out + MAX_LINE_LEAD_US))
            else -> maxOf(out, minOf(lineUs, out + MAX_LINE_LEAD_US))
        }
        lineNanos = nowNanos
        return out
    }

    private fun held(rawUs: Long, nowNanos: Long, rate: Double): Long {
        if (waitingForMove) {
            // Nothing can be heard before the play head moves: the line starts when it does.
            if (firstRawUs == UNSET) {
                firstRawUs = rawUs
            } else if (rawUs != firstRawUs) {
                waitingForMove = false
                movedNanos = nowNanos
            }
            ceilingNanos = nowNanos
        } else {
            ceilingUs += scaled(nowNanos - ceilingNanos, rate)
            ceilingNanos = nowNanos
        }

        // [KO] RG4-1: never further back than one AudioTrack buffer behind the reading.
        val limit = maxOf(ceilingUs + SLACK_US, rawUs - MAX_HOLD_US)
        val out = if (rawUs > limit) limit else rawUs
        holdUs = rawUs - out
        val ahead = rawUs - ceilingUs
        if (ahead > peakAheadUs) peakAheadUs = ahead
        if (!stepLogged && holdUs >= STEP_REPORT_US) {
            stepLogged = true
            log(
                "resume-clock: re-anchored after a ${pauseMs / 1000}s pause, step=-${holdUs / 1000}ms " +
                    "(the player read ahead of what could have played since the resume)",
            )
        }
        if (!summaryDone && !waitingForMove && nowNanos - movedNanos >= SUMMARY_AFTER_NANOS) {
            finishRun(nowNanos, "10s in")
        }
        return out
    }

    /** One line per resume worth one: what the player read against the line, signed. */
    private fun finishRun(nowNanos: Long, at: String) {
        if (!armed || summaryDone) return
        summaryDone = true
        if (pauseMs < SUMMARY_MIN_PAUSE_MS && peakAheadUs < STEP_REPORT_US) return
        val peak = if (peakAheadUs == Long.MIN_VALUE) 0L else peakAheadUs
        val sinceMoveMs = if (waitingForMove) -1L else (nowNanos - movedNanos) / 1_000_000L
        log(
            "resume-clock: after a ${pauseMs / 1000}s pause ($at, moved ${sinceMoveMs}ms ago): " +
                "peak lead=${signed(peak / 1000)}ms hold=${holdUs / 1000}ms",
        )
    }

    private fun scaled(elapsedNanos: Long, rate: Double): Long =
        (elapsedNanos.coerceAtLeast(0L) / 1000.0 * rate).toLong()

    private fun signed(ms: Long): String = if (ms >= 0) "+$ms" else "$ms"

    companion object {
        private const val UNSET = Long.MIN_VALUE

        /** Room above the line for a reading's own jitter; well under anything a reader sees. */
        const val SLACK_US = 100_000L

        /** How far the top line may stay above the readings: the 0.93 s sags, and no further. */
        const val MAX_LINE_LEAD_US = 1_200_000L

        /**
         * [KO] RG4-1: the largest hold. Media3 1.11 reports min(output position, frames written),
         * so the reading is at most one AudioTrack buffer ahead of the sound: 8 x 500 ms on the
         * playback-params route (G4-fix.md 4). A hold beyond it is behind anything playable.
         */
        const val MAX_HOLD_US = 4_000_000L

        /** A hold this large is a re-anchor worth a log line. */
        const val STEP_REPORT_US = 300_000L

        /** Every resume after a pause at least this long gets its summary line, held or not. */
        const val SUMMARY_MIN_PAUSE_MS = 2_000L
        const val SUMMARY_AFTER_NANOS = 10_000_000_000L

        private const val MIN_SPEED = 0.1
        private const val MAX_SPEED = 8.0
    }
}
