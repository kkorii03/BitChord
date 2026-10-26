package com.music.bitchord.download

import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier

/**
 * The decisions a batch high-quality re-download makes without touching the
 * network, the player or a file.
 *
 * Kept apart from [HqBatch] so the two things worth getting right — which
 * downloads can actually improve, and whether a new file is safe to put in
 * place of the old one — can be tested on a JVM. Everything here is a pure
 * function of what the app already knows.
 */
object HqBatchPolicy {

    enum class Category {
        /** A replacement can be attempted. */
        AVAILABLE,

        /** Already on the best rung this install can reach. */
        ALREADY_BEST,

        /** No higher copy is known or possible. */
        NONE,
    }

    /**
     * Which of the confirmation dialog's three counts [badge] falls into.
     *
     * [hasHigherSource] is [com.music.bitchord.data.sources.SourceResolver.canSubstituteForDownload]'s
     * answer: whether anything is ranked above YouTube at all. Without one, a
     * lossless file is already the ceiling, while a lossy one has nothing that
     * could beat it — the batch must not queue either as an upgrade.
     */
    fun category(badge: String?, verified: HqTier?, hasHigherSource: Boolean): Category {
        if (DownloadedQuality.isAtBest(badge, verified)) return Category.ALREADY_BEST
        if (verified != null || hasHigherSource) return Category.AVAILABLE
        val current = DownloadedQuality.tierOf(badge)
        return if (current.rank <= HqTier.LOSSLESS_16.rank) Category.ALREADY_BEST else Category.NONE
    }

    /** The rung to ask for when [category] is [Category.AVAILABLE], or null otherwise. */
    fun qualityFor(badge: String?, verified: HqTier?, hasHigherSource: Boolean): DownloadQuality? =
        if (category(badge, verified, hasHigherSource) == Category.AVAILABLE) {
            DownloadedQuality.qualityForUpgrade(badge, verified)
        } else {
            null
        }

    enum class Replacement {
        ACCEPT,
        UNREADABLE,
        TOO_LONG,
        TOO_SHORT,
    }

    /**
     * Whether a finished file may replace the one already saved.
     *
     * A replacement is only worth making when the new bytes are the same
     * recording: it has to decode, it must not be the music video's longer
     * sound, and it must not be a truncated transfer. The old file is left
     * alone for every answer but [Replacement.ACCEPT].
     */
    fun replacement(newDurationMs: Long?, catalogDurationMs: Long?): Replacement {
        if (newDurationMs == null || newDurationMs <= 0L) return Replacement.UNREADABLE
        if (catalogDurationMs == null || catalogDurationMs <= 0L) return Replacement.ACCEPT
        if (newDurationMs > catalogDurationMs + MAX_EXTRA_MS) return Replacement.TOO_LONG
        if (newDurationMs * 100 < catalogDurationMs * MIN_LENGTH_PERCENT) return Replacement.TOO_SHORT
        return Replacement.ACCEPT
    }

    /** The same slack the MV-sound verdict allows before a file is "longer". */
    private const val MAX_EXTRA_MS = 2_500L

    /** Shorter than three quarters of the catalogue length is a truncated file. */
    private const val MIN_LENGTH_PERCENT = 75L
}
