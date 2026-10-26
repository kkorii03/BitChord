package com.music.bitchord.download

import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqLibraryPolicy
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

    /**
     * The rung a saved file is actually on, using its verified provenance when
     * the badge cannot state it.
     *
     * A badge is the container and codec only: every FLAC file reads `FLAC`,
     * so a hi-res replacement would look like a plain lossless one on the next
     * pass and be fetched again. [storedHqTier] is the rung the verified pair
     * was proved at, and it wins over the badge.
     */
    fun tierOf(badge: String?, storedHqTier: String?): HqTier {
        val stored = storedHqTier
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { HqTier.valueOf(it.uppercase(Locale.ROOT)) }.getOrNull() }
        return HqLibraryPolicy.currentTier(tierOf(badge), stored)
    }

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
    fun isAtBest(badge: String?, verified: HqTier?, stored: HqTier? = null): Boolean {
        // The badge says only "FLAC" for every lossless file; the rung the
        // download recorded is the better witness when there is one.
        val current = listOfNotNull(tierOf(badge), stored).minBy { it.rank }
        if (current == HqTier.DOLBY_ATMOS) return true
        return verified != null && verified.rank >= current.rank
    }

    /**
     * The rung a replacement should ask for: the verified better rung when
     * there is one, otherwise the best the sources can offer.
     */
    fun qualityForUpgrade(badge: String?, verified: HqTier?): DownloadQuality =
        betterVerified(badge, verified)?.let { qualityFor(it) } ?: DownloadQuality.LOSSLESS

    /**
     * The format a saved badge describes, for callers that need to ask a
     * source whether it has something better than what is already on disk.
     *
     * Only the properties the badge can honestly state are filled in: a
     * lossless file is named FLAC, and a lossy one keeps the codec and the
     * bitrate the badge carries. Nothing is invented about depth or rate.
     */
    fun formatOf(badge: String?): StreamFormat {
        val value = badge?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return when {
            value.startsWith("DOLBY") -> StreamFormat(codec = "eac3-joc")
            value.startsWith("FLAC") || value.startsWith("ALAC") ->
                StreamFormat(codec = if (value.startsWith("ALAC")) "alac" else "flac")
            value.startsWith("AAC") ->
                StreamFormat(codec = "aac", kbps = KBPS.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull())
            value.startsWith("OPUS") ->
                StreamFormat(codec = "opus", kbps = KBPS.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull())
            value.startsWith("MP3") ->
                StreamFormat(codec = "mp3", kbps = KBPS.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull())
            else -> StreamFormat()
        }
    }

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
