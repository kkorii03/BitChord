package com.music.bitchord.data.innertube

/**
 * Turns a resolver's failure text into the one distinction playback needs:
 * a verdict about the recording, or a fact about this minute.
 *
 * The distinction cannot be read from the exception hierarchy alone. NewPipe
 * files a takedown, a region block and a truncated watch page under the same
 * [org.schabi.newpipe.extractor.exceptions.ParsingException] ancestry, and
 * InnerTubeX reports an exhausted catalog as a [StreamResolveException] whose
 * message is the only thing that says why. A message saying the upload was
 * blocked for a copyright claim is never going to change, so walking every
 * client again — and every extraction attempt after that — only repeats an
 * answer already in hand.
 *
 * Kept pure and message-based so it can be tested without Android, a network,
 * or either resolver. The returned string is the original message because it
 * is what the listener-facing log and the unplayable cache carry.
 */
object UnplayableReason {

    /**
     * Markers that describe the recording itself. Deliberately stronger than
     * the generic "unavailable" below: a region refusal and a takedown are
     * different answers, and only the latter should stop the direct path from
     * being tried for the next track.
     */
    private val trackSpecific = listOf(
        "claimed content",
        "blocked due to the claimed",
        "copyright claim",
        "copyrighted",
        "private video",
        "private content",
        "is private",
        "has been removed",
        "was removed",
        "deleted",
        "account terminated",
        "age-restricted",
        "paid content",
        "비공개",
        "삭제",
        "해지되었",
        "채널이 해지",
        "연령 제한",
        "유료 콘텐츠",
    )

    /**
     * The subset that means the upload itself is gone. Used to stop an
     * InnerTubeX walk from falling through to extraction: those messages name
     * a takedown, a claim, a deletion or a private upload, all of which read
     * the same on every client. A bare "UNPLAYABLE"/"unavailable" is left out
     * here because it is also how a region or Premium gate answers, and that
     * still has to reach the existing proxy retry.
     */
    private val definitelyGone = listOf(
        "claimed content",
        "blocked due to the claimed",
        "copyright claim",
        "copyrighted",
        "private video",
        "private content",
        "is private",
        "has been removed",
        "was removed",
        "deleted",
        "account terminated",
        "비공개",
        "삭제",
        "해지되었",
        "채널이 해지",
    )

    /**
     * Markers that settle the question even without a class to go on.
     * "UNPLAYABLE" is included because that is the player status InnerTubeX
     * and the anonymous clients report for a track nobody may serve.
     */
    private val definitive = trackSpecific + listOf(
        "unplayable",
        "login_required",
        "login required",
        "not available in your country",
        "이 국가에서는 이용할 수 없",
        "no longer available",
        "premium이 필요",
        "premium is required",
        "soundcloud go+",
        "sign in to confirm you are not a bot",
        "봇 확인",
    )

    /**
     * The resolver's own "unavailable" answers. Deliberately phrases rather
     * than the bare word: addon, module and Media3 failures also say
     * "unavailable", and those are not verdicts about the recording.
     */
    private val unavailableVerdicts = listOf(
        "video unavailable",
        "video is unavailable",
        "track unavailable",
        "track is unavailable",
        "streamresolveexception",
    )

    /**
     * Facts about the connection, not about the track. Checked before the
     * resolver's "unavailable" phrases so an HTTP 503 "Service Unavailable" is
     * not mistaken for a takedown.
     */
    private val transient = listOf(
        "429",
        "too many requests",
        "502",
        "503",
        "504",
        "service unavailable",
        "temporarily unavailable",
        "temporary",
        "timeout",
        "timed out",
        "read timed out",
        "connect timed out",
        "connection reset",
        "connection refused",
        "connection closed",
        "socket",
        "network",
        "unreachable",
        "try again",
        "retry",
        "no route to host",
        "broken pipe",
        "handshake",
        "ioexception",
    )

    /**
     * Why [message] means "never", or null if it only means "not just now".
     *
     * A strong recording marker wins over a transient word: a page that says
     * the upload was blocked for a claim is a verdict even if the same answer
     * arrived after a retryable request. Otherwise a timeout, a 429/503 or a
     * dropped socket reads as retryable, and a resolver's explicit
     * "track/video is unavailable" reads as a verdict because that is how both
     * resolvers phrase an exhausted catalog. A bare "unavailable" is not
     * enough: unrelated addon, module and Media3 errors use the same word.
     */
    fun fromMessage(message: String?): String? {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        if (trackSpecific.any { it in lower }) return text
        if (transient.any { it in lower }) return null
        if (definitive.any { it in lower }) return text
        // "This track is unavailable" is the generic text both resolvers use
        // for a region gate as well as a takedown, so it is a verdict but not
        // a track-specific one. It is matched by its phrase, not by the bare
        // word: "not available" and an unrelated module/addon "unavailable"
        // are deliberately not matched.
        if (unavailableVerdicts.any { it in lower }) return text
        return null
    }

    /** Whether [message] is a definitive verdict about the recording. */
    fun isDefinitive(message: String?): Boolean = fromMessage(message) != null

    /**
     * Whether [message] names a property of the recording itself, rather than
     * a region, a Premium gate or this minute's network. Used to keep such a
     * failure out of the path-learning bench: the direct path did nothing
     * wrong when the upload was taken down.
     */
    fun isTrackSpecific(message: String?): Boolean {
        val lower = message?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return false
        return trackSpecific.any { it in lower }
    }

    /**
     * The reason to remember when a second callback fires: a track-specific
     * verdict already in hand is never replaced by a generic failure, because
     * the claim/takedown is what has to settle the track.
     */
    fun keepTrackSpecific(existing: String?, incoming: String?): String? =
        if (isTrackSpecific(existing) && !isTrackSpecific(incoming)) existing else incoming

    /**
     * Whether [message] says the upload itself no longer exists, as opposed to
     * a region/Premium gate that may still play through the proxy.
     */
    fun isDefinitelyGone(message: String?): Boolean {
        val lower = message?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return false
        return definitelyGone.any { it in lower }
    }
}
