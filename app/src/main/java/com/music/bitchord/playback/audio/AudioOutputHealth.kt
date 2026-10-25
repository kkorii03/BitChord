package com.music.bitchord.playback.audio

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * A very small output-health meter for the audible sink.
 *
 * It is written from the audio thread on every block and read from the
 * service's existing five-second sampler — no timer of its own. The two facts
 * it keeps are enough to tell a stalled sink (frames stop arriving) from a
 * silent one (frames arrive, but every sample is zero), which are different
 * failures with different recovery paths.
 */
object AudioOutputHealth {

    /** A sample magnitude below this is silence for the purposes of recovery. */
    const val SILENT_PEAK = 1e-4f

    private val frames = AtomicLong(0L)

    @Volatile
    var lastBufferAtMs: Long = 0L
        private set

    @Volatile
    var lastNonSilentAtMs: Long = 0L
        private set

    /** Called on the audio thread after the DSP chain, before encoding. */
    fun onBuffer(framesWritten: Int, peak: Float) {
        if (framesWritten > 0) frames.addAndGet(framesWritten.toLong())
        val now = SystemClock.elapsedRealtime()
        lastBufferAtMs = now
        if (peak > SILENT_PEAK) lastNonSilentAtMs = now
    }

    fun processedFrames(): Long = frames.get()

    fun reset() {
        frames.set(0L)
        lastBufferAtMs = 0L
        lastNonSilentAtMs = 0L
    }
}
