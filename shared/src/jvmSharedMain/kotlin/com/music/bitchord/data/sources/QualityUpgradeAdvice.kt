package com.music.bitchord.data.sources

import java.util.Locale

/**
 * The pure half of the player menu's manual "Upgrade quality" action.
 *
 * The action is only ever a search among sources ranked above YouTube — see
 * [SourceResolver.upgradeFor][com.music.bitchord.data.sources.SourceResolver] —
 * so it has no possible answer when there is nothing above YouTube, or when
 * the track is already lossless or Dolby Atmos. Keeping that verdict here,
 * rather than in the service, makes it testable without a player, a source
 * registry or the network.
 */
object QualityUpgradeAdvice {

    enum class Outcome {
        /** A better stream was found. Whether it actually swapped is the player's answer. */
        SWAPPED,

        /** Nothing in the app can beat what is already playing. */
        ALREADY_BEST,

        /** Sources above YouTube were asked and none had a better copy. */
        NOTHING_BETTER,
    }

    /**
     * Whether a manual request is worth a network search at all.
     *
     * [hasHigherSource] is [SourceResolver.canSubstituteForYouTube]'s answer:
     * with only YouTube enabled, the manual path has no catalogue to ask and
     * the current YouTube stream is the ceiling this install can reach.
     */
    fun shouldSearch(
        currentIsLossless: Boolean,
        currentIsAtmos: Boolean,
        hasHigherSource: Boolean,
    ): Boolean = hasHigherSource && !currentIsLossless && !currentIsAtmos

    fun outcome(
        found: Boolean,
        currentIsLossless: Boolean,
        currentIsAtmos: Boolean,
        hasHigherSource: Boolean,
    ): Outcome = when {
        found -> Outcome.SWAPPED
        shouldSearch(currentIsLossless, currentIsAtmos, hasHigherSource) -> Outcome.NOTHING_BETTER
        else -> Outcome.ALREADY_BEST
    }

    /** "Opus 138kbps", "AAC 256kbps", "FLAC" — the short form a notice can carry. */
    fun label(format: StreamFormat?): String {
        if (format == null) return ""
        val codec = format.codec?.let(::displayCodec)
        val kbps = format.kbps?.takeIf { format.isLossless != true && !format.isDolbyAtmos }
        return listOfNotNull(codec, kbps?.let { "${it}kbps" }).joinToString(" ")
    }

    private fun displayCodec(codec: String): String = when (codec.lowercase(Locale.ROOT)) {
        "opus" -> "Opus"
        "aac", "mp4a", "mp4a-latm" -> "AAC"
        "mp3", "mpeg" -> "MP3"
        "flac" -> "FLAC"
        "alac" -> "ALAC"
        "vorbis" -> "Vorbis"
        "pcm", "raw", "wav" -> "PCM"
        "eac3-joc", "ec3-joc", "e-ac-3 joc", "dolby-atmos" -> "Dolby Atmos"
        else -> codec.uppercase(Locale.ROOT)
    }
}
