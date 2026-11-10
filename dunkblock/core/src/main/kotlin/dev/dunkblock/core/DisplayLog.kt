package dev.dunkblock.core

/**
 * What the test-pattern activity DREW, recorded on the UI thread from Choreographer.
 * Index i is the display-frame counter (the 16-bit value drawn on screen is i and 0xFFFF).
 *
 * Both times are on the System.nanoTime() timebase (Choreographer's documented timebase), the same
 * clock the capture side uses for arrivalNs, so they may be subtracted. [expectedPresentNs] is the
 * frame timeline's EXPECTED presentation time, not a measured photon time: treat differences against
 * it as estimates (about +-1 refresh period).
 */
class DisplayLog(val capacity: Int) {
    @Volatile var size = 0; private set
    val frameTimeNs = LongArray(capacity)
    val expectedPresentNs = LongArray(capacity)
    /** Flip state that starts at this frame (0/1), or -1 if no flip started here. */
    val flipState = ByteArray(capacity) { -1 }
    @Volatile var flips = 0; private set

    /** Returns the display-frame index (== counter to draw), or -1 when full. */
    fun recordFrame(frameTimeNs: Long, expectedPresentNs: Long): Int {
        val i = size
        if (i >= capacity) return -1
        this.frameTimeNs[i] = frameTimeNs
        this.expectedPresentNs[i] = expectedPresentNs
        size = i + 1
        return i
    }

    fun recordFlip(index: Int, state: Int) {
        if (index in 0 until size) { flipState[index] = state.toByte(); flips++ }
    }
}
