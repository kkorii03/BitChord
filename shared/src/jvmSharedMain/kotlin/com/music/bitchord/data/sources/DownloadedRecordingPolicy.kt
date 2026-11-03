package com.music.bitchord.data.sources

/**
 * When a downloaded file may be the first sound a listener hears.
 *
 * A download made from an add-on source is only a claim about a recording until
 * its sound has been compared with the YouTube song version. Playing it first
 * and discovering the claim was false nine seconds later is what put an AAC
 * file saved as `.flac` on the speaker before the health check could condemn it.
 * Such a file starts on the stream; the health check either proves it and the
 * player moves onto the file, or condemns it and the stream simply continues.
 *
 * Verified downloads and offline playback keep the old behaviour: the file is
 * the only sound there is when the network is gone.
 */
object DownloadedRecordingPolicy {

    /** Whether the saved file may be selected as the item's first audio. */
    fun fileFirst(
        downloaded: Boolean,
        wrongRecording: Boolean,
        verified: Boolean,
        offline: Boolean,
    ): Boolean {
        if (wrongRecording) return false
        if (!downloaded) return true
        return verified || offline
    }

    /**
     * Whether a completed check may record the file as verified.
     *
     * Only a fresh sound comparison is proof about these bytes. A stored
     * verdict proves the source/track pair, and an unreadable file proves
     * nothing; neither may promote a file to first-audio status.
     */
    fun recordsVerified(
        freshSoundAccepted: Boolean,
        verdict: DownloadHealthPolicy.Verdict,
    ): Boolean =
        freshSoundAccepted &&
            verdict != DownloadHealthPolicy.Verdict.WRONG_RECORDING &&
            verdict != DownloadHealthPolicy.Verdict.ARRANGEMENT
}
