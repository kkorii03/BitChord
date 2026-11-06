package com.music.bitchord.playback.audio

/**
 * Folds the 5.1 bed of an immersive track down to stereo.
 *
 * A Bluetooth route takes two channels: the phone hands A2DP a stereo track,
 * and a 6-channel one opened for an E-AC-3 JOC stream is never consumed there.
 * Measured on the S24 over a JBL Tour Pro 3 (phone 11-04): the played-frame
 * count froze every ~5 s and the silence guard rebuilt the AudioTrack, so an
 * Atmos upgrade became a gap every few seconds. Folding the bed here keeps the
 * track stereo, so the mix plays instead of stalling.
 *
 * Channel order is the one a Dolby decoder hands Android for 5.1:
 * FL FR FC LFE BL BR.
 *
 *     L' = (L + 0.707*C + 0.707*Ls) * ATTENUATION
 *     R' = (R + 0.707*C + 0.707*Rs) * ATTENUATION
 *
 * 0.707 (-3 dB) is the standard centre/surround fold level: a channel sent to
 * both outputs at unity would be 3 dB louder than the fronts it sits between.
 *
 * The LFE is left out. It is band-limited to 120 Hz, a phone speaker and an
 * A2DP link cannot reproduce it, and folding it in unfiltered would be a
 * rumble rather than a bass line.
 *
 * [ATTENUATION] is the reciprocal of the largest sum the three taps can reach
 * (1 + 0.707 + 0.707 = 2.414), so no input can clip whatever the decoder
 * sends: three correlated full-scale channels still land inside ±1.0. That is
 * a fixed ~7.7 dB below the worst case, and therefore conservative on real
 * material, whose channels are not correlated; the usual -3 dB overall would
 * clip on the peaks of a loud film mix instead, and a limiter that tracks the
 * content is a different piece of work.
 *
 * shortcut: only 5.1 is folded — that is the layout a Dolby decoder produces
 * here and the only one whose channel order is certain. A 7.1 bed keeps the
 * old path; add its SL/SR taps (and a 1/(1+3*0.707) gain) when a decoder
 * starts producing one.
 */
object Downmix {

    /** Channels in the bed this folds: FL FR FC LFE BL BR. */
    const val BED_CHANNELS: Int = 6

    /** -3 dB, the standard centre/surround fold level. */
    const val SURROUND_GAIN: Float = 0.70710677f

    /** 1 / (1 + 2 * SURROUND_GAIN): the largest fold that cannot clip. */
    const val ATTENUATION: Float = 0.41421357f

    /**
     * Whether a stream with [channelCount] channels has to be folded before it
     * can play on a stereo route.
     */
    fun needs(channelCount: Int): Boolean = channelCount == BED_CHANNELS

    /**
     * Whether a route change has to rebuild the output for the stream playing.
     *
     * The fold is decided when the sink configures, so a stream already playing
     * keeps the answer of the route it started on: earphones connected mid-song
     * would be handed the six-channel track that stalls them, and taking them off
     * would leave a mix folded on the speaker for no reason.
     */
    fun routeChangeRebuilds(channelCount: Int?, wasBluetooth: Boolean, isBluetooth: Boolean): Boolean =
        channelCount != null && needs(channelCount) && wasBluetooth != isBluetooth

    /**
     * Folds [source]'s 5.1 frames into [destination], one output frame per
     * input frame, in place in [destination]'s own buffer.
     */
    fun mix(source: AudioBlock, destination: AudioBlock) {
        require(source.channelCount == BED_CHANNELS) {
            "source must be a $BED_CHANNELS-channel bed: ${source.channelCount}"
        }
        require(destination.channelCount == 2) {
            "destination must be stereo: ${destination.channelCount}"
        }
        val frames = minOf(source.frameCount, destination.capacityFrames)
        val samples = source.samples
        val out = destination.samples
        var s = 0
        var d = 0
        for (frame in 0 until frames) {
            val left = samples[s]
            val right = samples[s + 1]
            val centre = samples[s + 2] * SURROUND_GAIN
            // s + 3 is the LFE, deliberately unread — see the class comment.
            val leftSurround = samples[s + 4] * SURROUND_GAIN
            val rightSurround = samples[s + 5] * SURROUND_GAIN
            out[d] = (left + centre + leftSurround) * ATTENUATION
            out[d + 1] = (right + centre + rightSurround) * ATTENUATION
            s += BED_CHANNELS
            d += 2
        }
        destination.reset(frames)
    }
}
