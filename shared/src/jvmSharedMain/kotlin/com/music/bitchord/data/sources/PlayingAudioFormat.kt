package com.music.bitchord.data.sources

import com.music.bitchord.data.NerdStats
import java.util.Locale

/**
 * One definition of "what is actually playing right now".
 *
 * The quality badge, the manual "Upgrade quality" decision and the player
 * menu's revert/upgrade row all have to answer the same question about the
 * same stream. They used to answer it from three different records — the
 * decoder, what a source promised, and the stored verification tier — so an
 * Opus stream could sit under a "Lossless" badge and a manual upgrade could be
 * refused as unnecessary because an earlier session had verified a FLAC.
 *
 * Everything here reads [NerdStats.current], which is built from the audio
 * renderer's own format. A null snapshot means the decoder has not spoken yet;
 * that is "unknown", never "lossless", so a search is still allowed rather
 * than being blocked by a stale claim.
 */
object PlayingAudioFormat {

    /** The snapshot only when it describes [mediaId], so a stale frame cannot answer for another track. */
    fun forTrack(mediaId: String?, snapshot: NerdStats.Snapshot?): NerdStats.Snapshot? {
        if (snapshot == null) return null
        if (mediaId == null || snapshot.mediaId == null) return snapshot
        return snapshot.takeIf { it.mediaId == mediaId }
    }

    fun isLossless(mediaId: String?, snapshot: NerdStats.Snapshot?): Boolean =
        forTrack(mediaId, snapshot)?.isLossless == true

    /** The same verdict the player's lossless badge draws. */
    fun showsLosslessBadge(mediaId: String?, snapshot: NerdStats.Snapshot?): Boolean =
        forTrack(mediaId, snapshot)?.showsLosslessBadge == true

    fun isDolbyAtmos(mediaId: String?, snapshot: NerdStats.Snapshot?): Boolean =
        forTrack(mediaId, snapshot)?.isDolbyAtmos == true

    fun isLosslessOrAtmos(mediaId: String?, snapshot: NerdStats.Snapshot?): Boolean =
        showsLosslessBadge(mediaId, snapshot) || isDolbyAtmos(mediaId, snapshot)

    /**
     * The measured stream as a [StreamFormat], or null before the decoder has
     * described it. The codec names follow [NerdStats]' own vocabulary so the
     * quality ladder recognises a lossless container from the measurement.
     */
    fun streamFormat(mediaId: String?, snapshot: NerdStats.Snapshot?): StreamFormat? {
        val playing = forTrack(mediaId, snapshot) ?: return null
        val mime = playing.mimeType ?: return null
        val codec = when {
            playing.isDolbyAtmos -> "eac3-joc"
            mime.endsWith("flac", ignoreCase = true) -> "flac"
            mime.endsWith("alac", ignoreCase = true) -> "alac"
            mime.startsWith("audio/dsd", ignoreCase = true) -> "dsf"
            NerdStats.isRawPcm(mime) -> "wav"
            else -> NerdStats.codecLabel(mime)?.lowercase(Locale.ROOT)
        } ?: return null
        return StreamFormat(
            codec = codec,
            kbps = playing.bitrateKbps,
            sampleRateHz = playing.sampleRateHz,
            bitDepth = playing.bitDepth,
        )
    }
}
