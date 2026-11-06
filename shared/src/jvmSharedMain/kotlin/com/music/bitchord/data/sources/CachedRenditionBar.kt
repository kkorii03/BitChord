package com.music.bitchord.data.sources

/**
 * Whether a rendition already on disk may be played as it is.
 *
 * A stream from a source is refused before the player starts on it when its
 * bytes contradict what its own row claimed — see [StreamContainerSniff] and
 * `KoStreamProbe`. A rendition *cached* from such a stream was written before
 * that check existed, and it is read straight off disk without ever being
 * resolved, so nothing that inspects a stream ever sees it. Measured (phone
 * 11-04, ILLIT "IYKYK"): every candidate for the track was correctly refused
 * with `claims FLAC 24-bit 44.1 kHz of 234s but the file runs 162s`, and the
 * track played wrong audio anyway, from a copy cached by an earlier play.
 *
 * So the same two questions are asked of the bytes on disk:
 *
 *  - **Does the container match the claim.** An entry tagged as a lossless
 *    rendition opens with a lossless container, or it is not one whatever it
 *    was written from.
 *  - **Is it the recording the row names.** A copy whose runtime is a
 *    [TrackMatcher.isWrongLength] away from the catalogue's is a different
 *    edit, not this song.
 *
 * A refusal means the bytes go — see [invalidatedKeys] for the one an addon
 * filled — and the track resolves normally, exactly as if nothing had been
 * cached for it.
 */
object CachedRenditionBar {

    /** The bucket a substitute's file is cached under; see `AudioCache`'s key factory. */
    const val SUBSTITUTE_SUFFIX = "#alt"

    /** Why a cached rendition may not be played as it is. */
    enum class Refusal {
        /** It says lossless and opens with a lossy container. */
        LOSSY_BYTES,

        /** Its runtime is not the row's. */
        DURATION,
    }

    /**
     * @param claimsLossless the entry is tagged as a lossless rendition, or
     *   carries an upgrade's rendition tag.
     * @param head the first bytes of the cached file, or null when they could
     *   not be read — an unreadable head is not evidence, exactly as it is not
     *   for a fresh stream.
     * @param rowSeconds the runtime the catalogue row states, when it states one.
     * @param renditionSeconds the runtime measured off the cached bytes.
     */
    fun refusal(
        claimsLossless: Boolean,
        head: ByteArray?,
        rowSeconds: Int?,
        renditionSeconds: Int?,
    ): Refusal? = when {
        claimsLossless && head != null && StreamContainerSniff.looksLossy(head) -> Refusal.LOSSY_BYTES
        TrackMatcher.isWrongLength(rowSeconds, renditionSeconds) -> Refusal.DURATION
        else -> null
    }

    /**
     * The cache keys a refusal for [mediaId] takes with it: the copy an addon
     * filled, so the same bad file cannot come back on the next play. The
     * YouTube entry and any upgraded rendition are left where they are — a
     * candidate refused now says nothing about bytes that were already proved.
     */
    fun invalidatedKeys(mediaId: String): List<String> =
        listOf("$mediaId$SUBSTITUTE_SUFFIX", "$mediaId$SUBSTITUTE_SUFFIX-noatmos")
}
