package com.music.bitchord.playback

/**
 * Which audio a local row's lyrics timing is measured against, and when.
 *
 * A downloaded file is the sound that is actually playing. The stream cache
 * holds nothing for it, so a measurement that only asks the cache can never
 * run — the downloaded cover stayed at `base=none` however long it played.
 * Reading the file directly is the same decode the streaming path does, from
 * the copy already on disk.
 *
 * A song's own file has no other recording to compare against; the caller
 * still decides that with `isVideo`/`fileTimingForeign`. This only says where
 * the audio comes from when a measurement is wanted.
 */
object LocalTimingPolicy {

    /**
     * The local file to decode for a measurement, or null when the row has no
     * usable file (a blank URI is not a source). A video's file and a file
     * whose lyrics were timed for another recording are both measured from
     * that file.
     */
    fun measurementUri(
        localUri: String?,
        isVideo: Boolean,
        timingForeign: Boolean,
    ): String? = localUri?.takeIf { it.isNotBlank() && (isVideo || timingForeign) }

    /**
     * Whether a row may start measuring now. A local file is already complete
     * on disk, so a video's file — or a file whose lyrics were timed for
     * another recording — can be read at once; a stream still waits for its
     * opening. A local song's own file has no other recording to compare
     * against, so it keeps the old answer (false).
     */
    fun measureNow(
        localUri: String?,
        isVideo: Boolean,
        timingForeign: Boolean,
    ): Boolean = localUri.isNullOrBlank() || isVideo || timingForeign

    /**
     * Whether a row's stored timing may be applied.
     *
     * A song's own downloaded file is timed for it already, so only a
     * correction by hand applies — unless a re-check wrote a fresh sheet for
     * this file, which exists precisely so its new sync is used. A video's
     * file and a stream are always adjustable.
     */
    fun baseAllowed(
        timed: Boolean,
        fromFile: Boolean,
        isVideo: Boolean,
        overrideRecheck: Boolean,
    ): Boolean = timed && (!fromFile || isVideo || overrideRecheck)
}
