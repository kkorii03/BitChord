package com.music.bitchord.playback.audio

import kotlin.math.abs

/**
 * Float-domain silence trimmer for the precision path.
 *
 * It sits in front of [DspChain] because that chain processes a block in place
 * and cannot change its frame count. Long runs of sub-threshold samples are
 * compacted out of the [AudioBlock] directly: there is no second sample buffer,
 * only the small fixed tail kept at the end of a cut so the next transient is
 * not clipped.
 *
 * Runs are counted across [process] calls, so a gap that straddles a decoder
 * block boundary is still recognised. Once a run reaches [minSilenceUs], the
 * frames still in the current block are dropped immediately; frames already
 * emitted from earlier blocks are not rewound. That keeps the detector
 * allocation-free and event-driven — one linear scan per block, no timer.
 */
class SilenceSkipper(
    private val minSilenceUs: Long = MIN_SILENCE_US,
    private val keptEachEndUs: Long = KEPT_EACH_END_US,
) {

    @Volatile
    var enabled: Boolean = false

    private var sampleRate: Int = 0
    private var channelCount: Int = AudioBlock.DEFAULT_CHANNELS

    private var minSilenceFrames: Int = 0
    private var keptFrames: Int = 1

    /** Ring of the most recent frames of a skipped run, replayed when it ends. */
    private var tail: FloatArray = FloatArray(0)
    private var tailFrames: Int = 0
    private var tailWrite: Int = 0

    /** Consecutive silent frames seen across calls, including a run in progress. */
    private var silentRunFrames: Int = 0
    private var skipping: Boolean = false

    /**
     * Frames permanently removed from the input since the last [reset].
     *
     * Frames currently held in [tail] are not counted: they are still going to
     * be written, just with the next block that carries music.
     */
    var skippedFrames: Long = 0L
        private set

    /**
     * Index of the first emitted frame relative to the first input frame of the
     * block just processed. Non-zero when a tail held from an earlier block is
     * replayed after some of this block's media time has already been skipped.
     * The caller uses it to align the emitted block's media timestamp.
     */
    var firstOutputFrameOffset: Int = 0
        private set

    fun configure(sampleRate: Int, channelCount: Int) {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
        if (this.sampleRate == sampleRate && this.channelCount == channelCount) return

        this.sampleRate = sampleRate
        this.channelCount = channelCount
        minSilenceFrames = ((sampleRate * minSilenceUs) / 1_000_000L).toInt().coerceAtLeast(1)
        keptFrames = ((sampleRate * keptEachEndUs) / 1_000_000L)
            .toInt()
            .coerceAtLeast(1)
            // Keeping both ends must stay inside the part that is cut, otherwise
            // flushing the tail could overwrite unread input in the same block.
            .coerceAtMost((minSilenceFrames / 2).coerceAtLeast(1))
        tail = FloatArray(keptFrames * channelCount)
        reset()
    }

    /** Clears the run state and the skipped-frame counter. [enabled] is left alone. */
    fun reset() {
        silentRunFrames = 0
        skipping = false
        tailFrames = 0
        tailWrite = 0
        skippedFrames = 0L
    }

    /**
     * Compacts [block] in place and updates its frame count.
     *
     * The position/offset accounting is the caller's job: compare
     * [skippedFrames] before and after the call to learn how many frames were
     * permanently dropped. [firstOutputFrameOffset] says where the emitted
     * frames begin in the block's media time.
     */
    fun process(block: AudioBlock) {
        firstOutputFrameOffset = 0
        if (!enabled || block.frameCount == 0 || sampleRate <= 0) return
        if (block.channelCount != channelCount) configure(sampleRate, block.channelCount)

        val inputFrames = block.frameCount
        val samples = block.samples
        val channels = channelCount
        val tailBefore = tailFrames

        var read = 0
        var write = 0
        var runStartWrite = -1
        var runStartedInBlock = false
        var firstOutputFrame = 0
        var hasOutput = false

        while (read < inputFrames) {
            val readOffset = read * channels
            if (isSilent(samples, readOffset, channels)) {
                if (silentRunFrames == 0) {
                    runStartWrite = write
                    runStartedInBlock = true
                }
                silentRunFrames++

                if (!skipping && silentRunFrames >= minSilenceFrames) {
                    // Keep the first frames of this run so the cut does not start
                    // with a click. If the run began in an earlier block, those
                    // frames already reached the delegate and only this block can
                    // be compacted.
                    write = if (runStartedInBlock) {
                        minOf(runStartWrite + keptFrames, write)
                    } else {
                        // Rewinding past the start of the block discards frames
                        // already copied, so no output has been produced yet and
                        // the offset must be taken from the replayed tail.
                        hasOutput = false
                        0
                    }
                    skipping = true
                    tailFrames = 0
                    tailWrite = 0
                }
                // Once skipping, only the threshold matters; capping keeps a
                // very long gap from overflowing the counter.
                if (skipping && silentRunFrames > minSilenceFrames) {
                    silentRunFrames = minSilenceFrames
                }

                if (skipping) {
                    tail[tailWrite * channels] = samples[readOffset]
                    for (channel in 1 until channels) {
                        tail[tailWrite * channels + channel] = samples[readOffset + channel]
                    }
                    tailWrite = (tailWrite + 1) % keptFrames
                    if (tailFrames < keptFrames) tailFrames++
                } else {
                    copyFrame(samples, readOffset, samples, write * channels, channels, write != read)
                    if (!hasOutput) {
                        firstOutputFrame = read
                        hasOutput = true
                    }
                    write++
                }
            } else {
                if (skipping) {
                    val tailLength = tailFrames
                    if (tailLength > 0 && write + tailLength <= read) {
                        write = flushTail(samples, write)
                        if (!hasOutput) {
                            firstOutputFrame = read - tailLength
                            hasOutput = true
                        }
                    } else if (tailLength > 0) {
                        // The run ended at the very start of this block, so its
                        // tail cannot be written without overwriting unread
                        // input. Drop it instead of corrupting the block; it is
                        // accounted as skipped below.
                        tailFrames = 0
                        tailWrite = 0
                    }
                    skipping = false
                }
                silentRunFrames = 0
                runStartWrite = -1
                runStartedInBlock = false
                copyFrame(samples, readOffset, samples, write * channels, channels, write != read)
                if (!hasOutput) {
                    firstOutputFrame = read
                    hasOutput = true
                }
                write++
            }
            read++
        }

        block.setFrameCount(write)
        // Everything that was not written is either dropped or still held in the
        // tail; only the dropped part belongs on the position offset.
        skippedFrames += (inputFrames - write - (tailFrames - tailBefore)).toLong()
        if (hasOutput) firstOutputFrameOffset = firstOutputFrame
    }

    private fun isSilent(samples: FloatArray, offset: Int, channels: Int): Boolean {
        for (channel in 0 until channels) {
            if (abs(samples[offset + channel]) > SILENCE_THRESHOLD) return false
        }
        return true
    }

    private fun copyFrame(
        source: FloatArray,
        sourceOffset: Int,
        destination: FloatArray,
        destinationOffset: Int,
        channels: Int,
        copy: Boolean,
    ) {
        if (!copy) return
        for (channel in 0 until channels) {
            destination[destinationOffset + channel] = source[sourceOffset + channel]
        }
    }

    private fun flushTail(samples: FloatArray, write: Int): Int {
        if (tailFrames == 0) return write
        var at = write
        val start = if (tailFrames < keptFrames) 0 else tailWrite
        repeat(tailFrames) { step ->
            val frame = (start + step) % keptFrames
            copyFrame(tail, frame * channelCount, samples, at * channelCount, channelCount, true)
            at++
        }
        tailFrames = 0
        tailWrite = 0
        return at
    }

    companion object {
        /** Same floor the 16-bit Media3 processor uses in this app. */
        const val MIN_SILENCE_US = 1_000_000L

        /** Media3's retention ratio against that floor, split across both ends. */
        const val KEPT_EACH_END_US = 100_000L

        /** Media3's `DEFAULT_SILENCE_THRESHOLD_LEVEL` of 1024 on a 16-bit scale. */
        const val SILENCE_THRESHOLD = 1024f / 32_768f
    }
}
