package com.music.bitchord.playback

/**
 * Why a transition was abandoned before either side became audible.
 *
 * The wire names are part of the log line (`crossfade: skipped a->b
 * reason=...`), so they are kept stable and free of prose.
 */
internal enum class CrossfadeSkipReason(val wire: String) {
    /** The two decoded formats cannot share an output path. */
    FORMAT_MISMATCH("format-mismatch"),

    /** The incoming player never became ready inside [CrossfadeArmPolicy.ARM_TIMEOUT_MS]. */
    ARM_TIMEOUT("arm-timeout"),

    /** The outgoing player advanced into the incoming item while we were still arming. */
    OUTGOING_MOVED("outgoing-moved"),

    /** The queue pointed at the item already playing, so there was nothing to fade into. */
    SAME_ITEM("same-item"),

    /** Read-ahead already settled that the incoming upload cannot be served. */
    UNPLAYABLE("unplayable"),
}

/**
 * The format facts that decide whether two tracks can overlap.
 *
 * Kept independent of Media3's `Format` so the decision is testable on the JVM.
 * A null field means "not known yet"; unknown never blocks a transition.
 */
internal data class CrossfadeTrackFormat(
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null,
    /** True when the player writes this side straight to hardware, bypassing the mixer. */
    val direct: Boolean = false,
    /** False for a stream that cannot be decoded to PCM and therefore cannot be mixed. */
    val decodable: Boolean = true,
)

/**
 * Pure policy for arming a crossfade: how long to wait, and whether the pair is
 * worth waiting on at all.
 *
 * Two ordinary AudioTracks at different sample rates are mixed and resampled
 * by the platform mixer, so a rate difference alone is not a reason to skip.
 * It becomes one only when either side is a direct output: two direct tracks
 * cannot be summed by AudioFlinger, and the second one opens into a route that
 * is already claimed. Channel layouts and non-PCM streams are also refused
 * rather than guessed at.
 */
internal object CrossfadeArmPolicy {
    /**
     * Grace after the standby lead, before a not-yet-ready player is dropped.
     *
     * The standby is armed [com.music.bitchord.playback.smart.AutomixWorkPolicy.STANDBY_LEAD_MS]
     * before the transition, so this is not the whole wait — it is how long the
     * fade is held past its start for a stream that is still resolving. A miss
     * is a plain gapless advance, which is always safe.
     */
    const val ARM_TIMEOUT_MS = 3_000L

    /** The deadline for one arming attempt, including any tempo-ramp beats. */
    fun armTimeoutMs(rampMs: Long = 0L): Long =
        (ARM_TIMEOUT_MS + rampMs.coerceAtLeast(0L)).coerceAtLeast(1L)

    /** Whether the two decoded tracks can be rendered at the same time. */
    fun canMix(outgoing: CrossfadeTrackFormat?, incoming: CrossfadeTrackFormat?): Boolean {
        if (outgoing == null || incoming == null) return true
        if (!outgoing.decodable || !incoming.decodable) return false

        val outChannels = outgoing.channelCount
        val inChannels = incoming.channelCount
        if (outChannels != null && inChannels != null && outChannels != inChannels) return false

        val outRate = outgoing.sampleRateHz
        val inRate = incoming.sampleRateHz
        if ((outgoing.direct || incoming.direct) &&
            outRate != null &&
            inRate != null &&
            outRate != inRate
        ) {
            return false
        }
        return true
    }
}
