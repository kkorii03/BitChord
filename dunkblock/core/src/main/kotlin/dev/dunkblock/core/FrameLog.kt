package dev.dunkblock.core

/**
 * Per-frame measurement log. Primitive arrays are allocated once up front so that recording a frame
 * performs no allocation and no I/O (the benchmark must not disturb what it measures).
 * Written by one capture thread; read after the session ends (or, racily, for the live display).
 *
 * Times are stored in the unit stated in each field name. Two clocks appear here and are NEVER
 * subtracted from one another without a timebase check (see [TimebaseCheck]):
 *  - arrivalNs   : System.nanoTime() inside the app (CLOCK_MONOTONIC on Android)
 *  - imageTsNs   : android.media.Image.getTimestamp() (timebase set by the producer; not assumed)
 */
class FrameLog(val capacity: Int) {
    @Volatile var size: Int = 0; private set
    var overflowed: Boolean = false; private set

    val arrivalNs = LongArray(capacity)
    val imageTsNs = LongArray(capacity)
    /** Images acquired in the same onImageAvailable call: 1 = no backlog, >=2 = consumer was behind. */
    val burst = ByteArray(capacity)
    /** Decoded on-screen frame counter (LATENCY test pattern), or -1 when absent/invalid. */
    val counter = IntArray(capacity)
    /** Luma (0..255) at the colour-flip probe point of the LATENCY pattern, or -1 when not in pattern mode. */
    val patLuma = IntArray(capacity)
    /** Mean luma x100 of a small box on the game's block button (GAME mode, landscape), or -1. Used to detect the user's own block presses. */
    val blockRoiLuma100 = IntArray(capacity) { -1 }
    /** Hash of ~1k sampled pixels; equal hashes on consecutive frames flag a sampled-duplicate. */
    val sampleHash = IntArray(capacity)
    val dupSampled = BooleanArray(capacity)
    /** Mean luma of the whole analysis frame x100 (0..25500); sustained ~0 => black frames (FLAG_SECURE?). */
    val meanLuma100 = IntArray(capacity)

    // Stage timings, microseconds, primary backend.
    val tCopyUs = IntArray(capacity)     // acquire + buffer access
    val tPreUs = IntArray(capacity)      // downscale + grayscale
    val tRoiUs = IntArray(capacity)      // ROI extraction
    val tMotionUs = IntArray(capacity)   // diff / threshold / features
    val tAnalysisUs = IntArray(capacity) // pre + roi + motion + detector (the "analysis" of the targets)
    val tTotalUs = IntArray(capacity)    // whole callback for this frame (incl. logging)
    // Secondary backend (BOTH mode), analysis only.
    val tAltAnalysisUs = IntArray(capacity)

    val globalMotion = FloatArray(capacity)
    val localMotion = FloatArray(capacity)

    /** Reserve the next slot; returns -1 once full (the session keeps running, frames just stop being logged). */
    fun next(): Int {
        val i = size
        if (i >= capacity) { overflowed = true; return -1 }
        return i
    }

    fun commit() { size = size + 1 }
}
