package com.music.bitchord.download

import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqQuality
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.StreamFormat
import java.util.Locale

/**
 * The quality rung a saved file already sits on, and the one a replacement
 * should ask for.
 *
 * A download record keeps a display badge — `FLAC`, `DOLBY`,
 * `AAC · 128 kbps` — rather than a [StreamFormat]. This turns that badge back
 * into the same ladder every other quality decision uses, so a downloaded
 * track can be compared with a verified higher candidate without inventing a
 * second ranking.
 */
object DownloadedQuality {

    /** The rung a saved file's badge describes; an unknown badge is the normal rung. */
    fun tierOf(badge: String?): HqTier {
        val value = badge?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (value.isEmpty()) return HqTier.NORMAL
        if (value.startsWith("DOLBY")) return HqTier.DOLBY_ATMOS
        if (value.startsWith("FLAC") || value.startsWith("ALAC")) return HqTier.LOSSLESS_16
        val kbps = KBPS.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val codec = when {
            value.startsWith("AAC") -> "aac"
            value.startsWith("OPUS") -> "opus"
            value.startsWith("MP3") -> "mp3"
            else -> null
        }
        return HqQuality.tierOf(StreamFormat(codec = codec, kbps = kbps))
    }

    /**
     * The verified rung that would actually beat [badge], or null when the
     * file is already on the best rung anything proved for this track.
     */
    fun betterVerified(badge: String?, verified: HqTier?): HqTier? =
        verified?.takeIf { it.rank < tierOf(badge).rank }

    /**
     * Whether the file can be called the best already.
     *
     * Only a proven answer counts: a verified rung at or below the file's own,
     * or the top rung by definition (Dolby Atmos). With nothing verified the
     * row stays actionable — "고음질로 다시 받기" — because silence is not
     * evidence that a better copy does not exist.
     */
    fun isAtBest(badge: String?, verified: HqTier?): Boolean {
        val current = tierOf(badge)
        if (current == HqTier.DOLBY_ATMOS) return true
        return verified != null && verified.rank >= current.rank
    }

    /**
     * The rung a replacement should ask for: the verified better rung when
     * there is one, otherwise the best the sources can offer.
     */
    fun qualityForUpgrade(badge: String?, verified: HqTier?): DownloadQuality =
        betterVerified(badge, verified)?.let { qualityFor(it) } ?: DownloadQuality.LOSSLESS

    /** The download rung that asks a source for [tier]'s copy. */
    fun qualityFor(tier: HqTier): DownloadQuality = when (tier) {
        HqTier.DOLBY_ATMOS -> DownloadQuality.HIGH
        HqTier.HI_RES_24_88,
        HqTier.HI_RES_24_44,
        HqTier.LOSSLESS_16 -> DownloadQuality.LOSSLESS

        HqTier.HIGH_LOSSY -> DownloadQuality.HIGH
        HqTier.NORMAL -> DownloadQuality.STANDARD
    }

    private val KBPS = Regex("""(\d+)\s*KBPS""")
}
