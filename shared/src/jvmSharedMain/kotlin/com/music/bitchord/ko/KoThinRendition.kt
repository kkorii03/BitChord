package com.music.bitchord.ko

import com.music.bitchord.data.sources.StreamFormat

/**
 * [KO] violin: a lossy row whose file is far smaller than the bitrate it claims.
 *
 * Phone 11-25: JioSaavn's `_320.mp4` for one song was 4 154 209 bytes for
 * 340 s, about 98 kbps, and replaced YouTube's copy under a 320 kbps badge.
 * The file's own size says what it is, before anything is compared.
 */
object KoThinRendition {

    fun thin(format: StreamFormat, durationSec: Int?, totalBytes: Long): Boolean {
        val claimed = format.kbps ?: return false
        if (format.isLossless != false || claimed < CLAIMED_KBPS) return false
        if (durationSec == null || durationSec < MIN_SECONDS || totalBytes <= 0L) return false
        return totalBytes * 8 / 1000 / durationSec < claimed * REAL_SHARE
    }

    private const val CLAIMED_KBPS = 256
    private const val MIN_SECONDS = 60

    /** VBR and a slightly shorter master stay far above this. */
    private const val REAL_SHARE = 0.5
}