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
        "copyright",
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
        "소유권 주장",
        "저작권",
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
        "copyright",
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
        "소유권 주장",
        "저작권",
    )

    /**
     * The copyright words on their own. They are in [definitelyGone] because a
     * claim message often carries nothing stronger, but the same words appear
     * in a country-scoped block ("blocked it in your country on copyright
     * grounds"). That refusal is about where the listener is, and the proxy
     * may still serve it, so [regionScoped] cancels these alone.
     */
    private val copyrightOnly = listOf(
        "copyright",
        "copyrighted",
        "저작권",
    )

    /**
     * Wording that scopes a refusal to the listener's country. A claim or a
     * takedown is still a fact about the recording wherever it is read; a
     * copyright notice carrying one of these is a region gate first.
     */
    private val regionScoped = listOf(
        "in your country",
        "in this country",
        "your country",
        "this country",
        "in your region",
        "in this region",
        "your region",
        "이 국가",
        "국가에서는",
        "국가에서",
        "지역에서는",
        "지역에서",
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
    )

    /**
     * The "sign in to prove you are not a bot" answer.
     *
     * Deliberately *not* in [definitive]: it is a property of the network the
     * request left from, not of the recording. Treating it as a per-track
     * verdict made every track in the queue look broken at once and the player
     * skip through them, while the same track plays again the moment the
     * challenge clears. Callers that must stop rather than skip ask
     * [isBotChallenge] directly.
     */
    private val botChallenge = listOf(
        "sign in to confirm you are not a bot",
        "sign in to confirm you're not a bot",
        "confirm you are not a bot",
        "confirm you're not a bot",
        "not a bot",
        "봇이 아님",
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
        // A bot challenge can arrive as LOGIN_REQUIRED, whose marker would
        // otherwise make it a definitive verdict before the challenge is even
        // considered. It is a property of the network, so it is never a
        // verdict about this recording.
        if (isBotChallenge(text)) return null
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
     * Markers of a refusal the proxy may fix: a country gate, the Korea
     * no-Premium gate, or the bot challenge. Deliberately narrower than
     * [isDefinitive] — a takedown or a private upload is not helped by leaving
     * from another address.
     */
    private val regionGate = listOf(
        "not available in your country",
        "이 국가에서는 이용할 수 없",
        "youtubemusicpremiumcontent",
        "youtube music premium",
        "music premium",
        "프리미엄",
        "premium이 필요",
        "premium is required",
    )

    /**
     * Whether [message] names a refusal a proxy walk may answer.
     *
     * The bot challenge is included because it is the same kind of
     * network-level answer. Used by the download path, which otherwise only
     * tried the proxy for a bot exception and left a gated download failing
     * even when a proxy was configured.
     */
    fun isRegionGate(message: String?): Boolean {
        val lower = message?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return false
        if (isBotChallenge(lower)) return true
        return regionGate.any { it in lower }
    }

    /**
     * Whether [message] is YouTube's bot challenge rather than a verdict about
     * the recording. A challenge is network-wide: it must not be remembered
     * per videoId and must stop playback rather than walk the queue.
     */
    fun isBotChallenge(message: String?): Boolean {
        val lower = message?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return false
        return botChallenge.any { it in lower }
    }

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
     * The reason to keep across a walk for the *proxy* decision: a region,
     * Premium or bot answer must survive a later timeout, or the download path
     * loses the one signal that earns its single proxy walk and the bot stop.
     *
     * [keepTrackSpecific] deliberately keeps only the claim/takedown set, which
     * means a region refusal followed by a timed-out client used to be replaced
     * by the timeout. A track-specific verdict still wins over a network
     * answer, because it is the stronger fact about the recording; everything
     * else only survives if the incoming one is weaker.
     */
    fun keepProxyWorthy(existing: String?, incoming: String?): String? {
        if (existing == null) return incoming
        if (incoming == null) return existing
        if (isTrackSpecific(incoming)) return incoming
        if (isTrackSpecific(existing)) return existing
        if (isRegionGate(existing) && !isRegionGate(incoming)) return existing
        if (isBotChallenge(existing) && !isBotChallenge(incoming)) return existing
        return incoming
    }

    /**
     * Whether [message] says the upload itself no longer exists, as opposed to
     * a region/Premium gate that may still play through the proxy.
     */
    fun isDefinitelyGone(message: String?): Boolean {
        val lower = message?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return false
        // A claim, takedown or deletion settles the recording even if the
        // message also names a country. Only the bare copyright words defer
        // to a region qualifier.
        if (definitelyGone.any { it in lower && it !in copyrightOnly }) return true
        if (regionScoped.any { it in lower }) return false
        return copyrightOnly.any { it in lower }
    }
}
