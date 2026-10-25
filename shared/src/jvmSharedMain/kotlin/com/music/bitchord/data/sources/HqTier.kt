package com.music.bitchord.data.sources

/**
 * The quality ladder every source decision is made on.
 *
 * The rungs are the listener's, not a catalogue's marketing: a 24-bit/96 kHz
 * master, a 24-bit/44.1–48 kHz one, an ordinary 16-bit lossless file, a good
 * lossy copy, and everything at or below the rate YouTube already serves.
 * [rank] is ordered so that **lower is better**, which lets callers sort
 * ascending and compare with a single subtraction.
 *
 * The ladder is also what catches a fake: a row labelled 24-bit FLAC whose
 * stated bitrate is what a 16-bit file costs, or a "FLAC" at 250 kbps, is
 * dropped at least one rung before it can beat a real copy. See [HqQuality].
 */
enum class HqTier(val rank: Int, val label: String) {
    /** Dolby Atmos: immersive rather than bit-exact, and kept ahead of lossless as before. */
    DOLBY_ATMOS(0, "Dolby Atmos"),

    /** 24-bit or more, 88.2 kHz or more. */
    HI_RES_24_88(1, "Hi-Res 24-bit 88.2kHz+"),

    /** 24-bit or more, 44.1/48 kHz. */
    HI_RES_24_44(2, "Hi-Res 24-bit 44.1/48kHz"),

    /** 16-bit lossless, 44.1/48 kHz — or a lossless copy that did not state its depth/rate. */
    LOSSLESS_16(3, "Lossless 16-bit 44.1/48kHz"),

    /** AAC/MP3 256–320 kbps, Opus 256. */
    HIGH_LOSSY(4, "High lossy 256–320kbps"),

    /** Opus/AAC at or below 160 kbps, and anything that did not describe itself. */
    NORMAL(5, "Normal 160kbps or below"),
}

/**
 * One definition of "which copy is better", used by the live path, the second
 * look, downloads and the ahead-of-playback preparation.
 *
 * The order is the user's ladder first, then the rate actually stated for
 * copies on the same rung, and nothing else. Source order is deliberately not
 * here: it is a property of the configured list, not of a stream, and the
 * callers that need it break ties by list position.
 */
object HqQuality {

    /** The rung [format] belongs on, after demoting a copy that overstates itself. */
    fun tierOf(format: StreamFormat?): HqTier {
        if (format == null) return HqTier.NORMAL
        if (format.isDolbyAtmos) return HqTier.DOLBY_ATMOS
        val kbps = format.kbps?.takeIf { it > 0 }
        if (format.isLossless == true) {
            // A lossless label with a lossy rate is a transcode wearing the
            // label. Demote it rather than letting the label win a comparison.
            if (kbps != null && kbps < FAKE_LOSSLESS_KBPS) {
                return if (kbps < NORMAL_KBPS) HqTier.NORMAL else HqTier.HIGH_LOSSY
            }
            val depth = format.bitDepth
            val rate = format.sampleRateHz
            val declared = when {
                depth != null && depth >= 24 && rate != null && rate >= HI_RES_RATE_HZ -> HqTier.HI_RES_24_88
                depth != null && depth >= 24 && rate != null && rate in BASE_RATES_HZ -> HqTier.HI_RES_24_44
                depth != null && depth >= 16 && rate != null && rate in BASE_RATES_HZ -> HqTier.LOSSLESS_16
                // Nothing stated: a lossless codec is still lossless, but it
                // cannot claim a hi-res rung it never described.
                else -> HqTier.LOSSLESS_16
            }
            // A 24-bit label at or below what 16-bit/44.1 stereo costs is a
            // fake hi-res. Drop one rung (or more when the rate is thin).
            if (declared == HqTier.HI_RES_24_88 && kbps != null && kbps <= SIXTEEN_BIT_KBPS) {
                return HqTier.HI_RES_24_44
            }
            if (declared == HqTier.HI_RES_24_44 && kbps != null && kbps <= SIXTEEN_BIT_KBPS) {
                return HqTier.LOSSLESS_16
            }
            return declared
        }
        return when {
            kbps == null -> HqTier.NORMAL
            kbps >= HIGH_LOSSY_KBPS -> HqTier.HIGH_LOSSY
            else -> HqTier.NORMAL
        }
    }

    /**
     * The rung a search row advertises, for ordering candidates before any of
     * them has been opened. The row's label is all there is at this point, so
     * a hi-res label is placed on the lower hi-res rung until the stream says
     * otherwise — never above what it can prove.
     */
    fun tierOfDeclared(sourceQuality: String?, hiRes: Boolean): HqTier =
        when (sourceQuality?.uppercase(java.util.Locale.ROOT)) {
            "DOLBY" -> HqTier.DOLBY_ATMOS
            "HI-RES", "HIRES" -> HqTier.HI_RES_24_44
            "LOSSLESS", "FLAC", "ALAC", "WAV" ->
                if (hiRes) HqTier.HI_RES_24_44 else HqTier.LOSSLESS_16
            "HIGH", "320" -> HqTier.HIGH_LOSSY
            else -> HqTier.NORMAL
        }

    /** Whether [format] is on one of the three lossless rungs, fake hi-res demotions included. */
    fun isLosslessTier(format: StreamFormat?): Boolean {
        if (format?.isLossless != true) return false
        return tierOf(format).rank in HqTier.HI_RES_24_88.rank..HqTier.LOSSLESS_16.rank
    }

    /** Whether [format] claims more than its own bitrate can support. */
    fun isFakeHighQuality(format: StreamFormat?): Boolean {
        if (format == null) return false
        val kbps = format.kbps ?: return false
        if (format.isLossless == true) {
            if (kbps < FAKE_LOSSLESS_KBPS) return true
            val depth = format.bitDepth
            return depth != null && depth >= 24 && kbps <= SIXTEEN_BIT_KBPS
        }
        return false
    }

    /**
     * Whether [candidate] is a better copy than [current].
     *
     * Same rung compares the stated bitrate; when that is equal or missing,
     * depth and sample rate decide. A null [current] is beaten by anything,
     * as before.
     */
    fun isBetter(candidate: StreamFormat?, current: StreamFormat?): Boolean {
        if (candidate == null) return false
        if (current == null) return true
        val candidateTier = tierOf(candidate)
        val currentTier = tierOf(current)
        if (candidateTier.rank != currentTier.rank) return candidateTier.rank < currentTier.rank
        val candidateKbps = candidate.kbps ?: 0
        val currentKbps = current.kbps ?: 0
        if (candidateKbps != currentKbps) return candidateKbps > currentKbps
        val depth = (candidate.bitDepth ?: 0).compareTo(current.bitDepth ?: 0)
        if (depth != 0) return depth > 0
        return (candidate.sampleRateHz ?: 0) > (current.sampleRateHz ?: 0)
    }

    /** The better of two streams on the ladder, or [current] when neither wins. */
    fun betterOf(current: SourceStream?, candidate: SourceStream): SourceStream =
        if (current == null || isBetter(candidate.format, current.format)) candidate else current

    /** The gain in rungs from [current] to [candidate] (positive means better). */
    fun rungsGained(candidate: StreamFormat?, current: StreamFormat?): Int =
        tierOf(current).rank - tierOf(candidate).rank

    /** A short label for logs: the rung and, when stated, the rate. */
    fun label(format: StreamFormat?): String {
        val tier = tierOf(format)
        val kbps = format?.kbps?.takeIf { it > 0 }
        return if (kbps != null) "${tier.label} ${kbps}kbps" else tier.label
    }

    /** 24-bit/96 kHz stereo PCM, the usual "hi-res" threshold. */
    private const val HI_RES_RATE_HZ = 88_200

    private val BASE_RATES_HZ = 44_100..48_000

    /** 16-bit/44.1 kHz stereo PCM — anything at or below this is not 24-bit audio. */
    private const val SIXTEEN_BIT_KBPS = 1_411

    /** Below this a file claiming lossless is a lossy transcode. */
    private const val FAKE_LOSSLESS_KBPS = 300

    /** At or below this a lossy stream is the normal rung, not the high one. */
    private const val NORMAL_KBPS = 160

    /** The bottom of the high-lossy rung (AAC/MP3 256, Opus 256). */
    private const val HIGH_LOSSY_KBPS = 256
}
