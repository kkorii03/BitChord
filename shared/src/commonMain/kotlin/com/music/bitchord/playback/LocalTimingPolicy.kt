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
     * Whether timed lines from a downloaded file may be moved onto the clock of
     * the sound that is playing. A file's own embedded timing is left alone, but
     * a video's file and a re-checked override are timed for the file's audio,
     * so their stored answer is valid here just as it is on the lyrics screen.
     *
     * [recheckOverride] is the per-song sheet written by a downloaded-file
     * re-check; [sourceTimedToVideo] keeps a video's own captions from being
     * moved by a record-based answer.
     */
    fun downloadedTimingTimeable(
        timed: Boolean,
        fromFile: Boolean,
        isVideo: Boolean,
        recheckOverride: Boolean,
        sourceTimedToVideo: Boolean,
    ): Boolean = timed && (!fromFile || isVideo || (recheckOverride && !sourceTimedToVideo))

    /**
     * Whether a stored measurement or AI answer may be applied or recorded for
     * what is playing. A substitute stream is a different recording under the
     * same video id, so its clock is not the YouTube upload's: neither apply
     * an answer made for the upload nor spend an engine on the substitute.
     * A video's own upload is never treated as substituted here.
     */
    fun streamTimingAllowed(
        timeable: Boolean,
        isVideo: Boolean,
        substituted: Boolean,
    ): Boolean = timeable && !(substituted && !isVideo)
}
