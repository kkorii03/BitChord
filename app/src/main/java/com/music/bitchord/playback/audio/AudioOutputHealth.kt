package com.music.bitchord.playback.audio

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * A very small output-health meter for the audible sink.
 *
 * It is written from the audio thread on every block and read from the
 * service's existing five-second sampler — no timer of its own. It keeps
 * enough facts to tell a stalled sink (frames stop arriving) from a silent one
 * (frames arrive, but every sample is zero), and to tell a broken gain stage
 * from a genuinely quiet passage by comparing the decoder's own level with the
 * level after the DSP chain.
 *
 * The onset timestamps are cleared by [reset], which is called on a track
 * change and never by a recovery step: after a rebuild the output is still the
 * thing being judged, and clearing the clock would make a permanently silent
 * sink look healthy forever because [lastNonSilentAtMs] never gets a fresh
 * value.
 */
object AudioOutputHealth {

    /** A sample magnitude below this is silence for the purposes of recovery. */
    const val SILENT_PEAK = AudioSilenceRecovery.SILENT_PEAK

    /** Allowance for the DSP chain's lead when comparing the two silence onsets. */
    private const val DECODER_SILENCE_GRACE_MS = 500L

    private val frames = AtomicLong(0L)

    @Volatile
    var lastBufferAtMs: Long = 0L
        private set

    @Volatile
    var lastNonSilentAtMs: Long = 0L
        private set

    /** When the post-gain output last turned zero; 0 while it is audible. */
    @Volatile
    var outputSilentSinceMs: Long = 0L
        private set

    /** When the decoder output last turned zero; 0 while it carries samples. */
    @Volatile
    var decoderSilentSinceMs: Long = 0L
        private set

    /**
     * Called on the audio thread after the DSP chain, before encoding.
     *
     * @param decodedPeak largest magnitude leaving the decoder, before the DSP
     *   chain and therefore before any fader or filter the app applies.
     * @param outputPeak largest magnitude after the DSP chain.
     */
    fun onBuffer(framesWritten: Int, decodedPeak: Float, outputPeak: Float) {
        if (framesWritten > 0) frames.addAndGet(framesWritten.toLong())
        val now = SystemClock.elapsedRealtime()
        lastBufferAtMs = now
        if (outputPeak > SILENT_PEAK) {
            lastNonSilentAtMs = now
            outputSilentSinceMs = 0L
        } else if (outputSilentSinceMs == 0L) {
            outputSilentSinceMs = now
        }
        if (decodedPeak > SILENT_PEAK) {
            decoderSilentSinceMs = 0L
        } else if (decoderSilentSinceMs == 0L) {
            decoderSilentSinceMs = now
        }
    }

    fun processedFrames(): Long = frames.get()

    /** How long the post-gain output has been zero at [nowMs], or 0 if audible. */
    fun outputSilentForMs(nowMs: Long): Long =
        if (outputSilentSinceMs > 0L) (nowMs - outputSilentSinceMs).coerceAtLeast(0L) else 0L

    /** How long the decoder itself has been zero at [nowMs], or 0 if audible. */
    fun decoderSilentForMs(nowMs: Long): Long =
        if (decoderSilentSinceMs > 0L) (nowMs - decoderSilentSinceMs).coerceAtLeast(0L) else 0L

    /**
     * Whether the decoder itself has been quiet for the whole of the current
     * output silence. A small grace covers the DSP chain's own lead: it may
     * write the last audible block a moment after the output-side onset.
     */
    fun decoderSilentThroughoutOutput(): Boolean {
        val outputSince = outputSilentSinceMs
        if (outputSince == 0L) return false
        val decoderSince = decoderSilentSinceMs
        // The decoder is still producing samples after the output went zero:
        // something between them is at fault.
        if (decoderSince == 0L) return false
        return decoderSince <= outputSince + DECODER_SILENCE_GRACE_MS
    }

    fun reset() {
        frames.set(0L)
        lastBufferAtMs = 0L
        lastNonSilentAtMs = 0L
        outputSilentSinceMs = 0L
        decoderSilentSinceMs = 0L
    }
}
