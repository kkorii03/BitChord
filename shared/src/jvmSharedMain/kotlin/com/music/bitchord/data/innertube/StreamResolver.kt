package com.music.bitchord.data.innertube

import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.Http
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.net.BlockedStartPolicy
import com.music.bitchord.data.net.BlockedStartStep
import com.music.bitchord.data.net.LogUrl
import com.music.bitchord.data.net.ProxyFailure
import com.music.bitchord.data.net.ProxyHealthMonitor
import com.music.bitchord.data.net.ProxyPlayback
import com.music.bitchord.data.net.ProxyPlaybackPolicy
import com.music.bitchord.data.net.ResolutionFacts
import com.music.bitchord.data.net.YouTubeProxy
import com.music.bitchord.data.net.raceForFirstSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Turns a videoId into a URL ExoPlayer can actually stream.
 *
 *  1. **InnerTubeX first.** Its live-benchmarked client catalog, cipher tiers
 *     and PoTokens decide which identity asks and unlock what it answers. See
 *     [InnerTubeXResolver].
 *
 *  2. **NewPipe as the failsafe.** It re-derives everything from the watch
 *     page, which is several hundred kilobytes of HTML Google rate-shapes under
 *     load, so it is kept off the hot path and reached only when InnerTubeX
 *     finds nothing. See [newPipeStream].
 *
 *  3. **Whether the URL is real.** A URL can be dead on arrival for reasons no
 *     amount of care predicts, so nothing is handed to the player, or cached,
 *     until bytes have been fetched from it. See [probe].
 */
object StreamResolver {
    /** The bitrate ceiling in force — the phone's quality setting for the connection it is on. */
    @Volatile
    var maxKbps: () -> Int = { Int.MAX_VALUE }


    private const val TAG = "BitChord"

    /** Past this, an extractor fetch is worth flagging rather than just noting. */
    private const val SLOW_FETCH_MS = 2000L

    /** See [OkHttpDownloader.execute] — the one request the extractor is not allowed to make. */
    private const val NEXT_ENDPOINT = "/youtubei/v1/next"

    /** Well-formed, empty, and over the library's fifty-character floor. */
    private const val EMPTY_NEXT_RESPONSE =
        """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""

    /** NewPipe needs a Downloader; reuse the app's single OkHttp client. */
    private class OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            // The `next` endpoint answers "what plays after this" — related
            // videos and the autoplay queue. `fetchPage()` asks for it
            // unconditionally while building a StreamExtractor, and this app
            // never reads the answer: it is here for [audioStreams] and gets
            // its own up-next from [Innertube.next] on a different client.
            //
            // Declining it is worth a special case because of what it costs.
            // Measured across extractions, every other request in the chain
            // lands in 110-670ms, while this one takes seven seconds or simply
            // hangs — it was the single request behind
            // `extractor fetch FAILED after 12004ms`, and since one hung call
            // fails the whole attempt, an endpoint nothing here reads was
            // deciding whether a track played at all.
            //
            // Answered with an empty-but-well-formed envelope rather than an
            // error, so nothing downstream treats it as a failed fetch; NewPipe
            // finds no related items, which is exactly as many as are wanted.
            // It has to be this padded: the library rejects any JSON body under
            // fifty characters outright with "JSON response is too short", so a
            // bare `{}` fails the whole extraction rather than quietly
            // returning nothing.
            if (NEXT_ENDPOINT in request.url()) {
                return Response(200, "OK", emptyMap(), EMPTY_NEXT_RESPONSE, request.url())
            }
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())

            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) {
                    hasUserAgent = true
                }
                when {
                    values.size > 1 -> {
                        builder.removeHeader(name)
                        values.forEach { builder.addHeader(name, it) }
                    }
                    values.size == 1 -> builder.header(name, values[0])
                }
            }
            if (!hasUserAgent) {
                // Chrome, not Firefox: NewPipe's own internal fetches — the
                // player JS included — go out under whatever this default is.
                // PixelMusic-ref's equivalent downloader defaults to this same
                // Chrome UA and does not hit the "Could not parse
                // deobfuscation function" failure this app was getting on the
                // identical video and NewPipeExtractor version; a Firefox UA
                // here is the one input that differed.
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                )
            }

            // Every byte NewPipe fetches passes through here, and until this
            // line none of it was visible: an extraction that took thirty
            // seconds reported thirty seconds, with nothing to say whether that
            // was one shaped watch page, a player POST, or the player
            // JavaScript. Naming the request and its size is what makes the
            // difference between measuring the step and guessing at it.
            val requestStart = MonotonicClock.nowMs()
            val response = try {
                extractorClient.newCall(builder.build()).execute()
            } catch (e: Exception) {
                // The one that matters most, and the one a log written only on
                // the way out never sees: a request that times out or is torn
                // down produces no line at all, so an extraction killed by a
                // single hung call looks like an extraction that was slow for
                // no reason. Named here, then rethrown unchanged.
                TrackLog.w(
                    TAG,
                    "extractor fetch FAILED after ${MonotonicClock.nowMs() - requestStart}ms " +
                        "${request.httpMethod()} ${LogUrl.of(request.url())}: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
            val took = MonotonicClock.nowMs() - requestStart
            if (took > SLOW_FETCH_MS) {
                TrackLog.w(TAG, "extractor fetch ${took}ms ${request.httpMethod()} ${LogUrl.of(request.url())}")
            } else {
                TrackLog.d(TAG, "extractor fetch ${took}ms ${request.httpMethod()} ${LogUrl.of(request.url())}")
            }
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    private val init by lazy { NewPipe.init(OkHttpDownloader()) }

    /**
     * The extractor's own leash on [Http.client].
     *
     * Everything NewPipe fetches — the watch page above all — goes out through
     * here, and [Http.client] sets no `callTimeout` at all. Its 30-second read
     * timeout does not stand in for one: a read timeout is per read, so a
     * response that yields a few bytes at a time resets it forever and the call
     * never ends. That is not a hypothetical failure mode but the exact shaping
     * this file's header describes Google applying to the watch page, and it
     * was observed doing it — an extraction that simply never returned, twice
     * in a row, leaving a track buffering until ExoPlayer gave up and retried
     * into the same wall. Unbounded is the one thing this call must not be,
     * because it is the failsafe: nothing runs after it.
     *
     * It gets its **own connection pool**, and that is the point of it rather
     * than a detail. Sharing [Http.client]'s pool means the watch-page GET can
     * be handed a connection to `www.youtube.com` left over from an Innertube
     * `player` POST, and a pooled connection that the far end has quietly
     * stopped answering does not fail — it hangs, silently, until something
     * times it out. `retryOnConnectionFailure` cannot save it, because nothing
     * is failing. That is exactly the shape the measurements have: a first
     * attempt that burns the whole ceiling and a second, on a fresh connection,
     * that succeeds in 2.2 seconds. Its own pool means extraction never
     * inherits a socket some other part of the app finished with.
     *
     * Sharing the pool was never required, either. The address-family argument
     * in [Http] is about a googlevideo media fetch matching the `player`
     * request that minted its URL; this fetches HTML from `www.youtube.com`,
     * and the googlevideo URL it comes back with is fetched later through
     * [Http.client] regardless.
     *
     * The ceiling is sized against a healthy extraction — about two seconds —
     * rather than against patience. Twelve is generous enough that a merely
     * slow page still completes, and short enough that a hung one costs a few
     * seconds before [EXTRACTION_ATTEMPTS] tries again on a new connection,
     * instead of half a minute of silence.
     */
    private val extractorClient by lazy {
        Http.client.newBuilder()
            // Short, because a connection this app is *not* using is a
            // connection going stale. The observed failure is a request that
            // gets no response at all and dies on the ceiling exactly — twelve
            // thousand and one milliseconds, over and over, on three different
            // endpoints. That is not a slow server, it is a socket the far side
            // (or a NAT on the way) has silently dropped while it sat idle
            // between tracks, being handed to the next request as though it
            // were good. Half a minute of keepalive is short enough that most
            // are re-established rather than resurrected.
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            // And for the ones that go stale while held: HTTP/2 pings make the
            // client notice a dead peer itself, in seconds, instead of waiting
            // out a response that is never coming. These endpoints are all
            // HTTP/2, so this is the mechanism actually available for detecting
            // it rather than merely giving up on it.
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .callTimeout(EXTRACTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private const val PING_INTERVAL_SECONDS = 5L

    /**
     * Sized so that giving up and trying again is cheaper than waiting.
     *
     * A healthy extractor request lands in 100-700ms and the retry that follows
     * a hung one has, every time it has been watched, succeeded immediately —
     * so the ceiling is not protecting a slow-but-viable request, it is deciding
     * how long a dead connection costs. At twelve seconds one stall turned a
     * four-second start into twenty-six; at five, the same stall costs about
     * six seconds all in.
     */
    private const val EXTRACTOR_TIMEOUT_SECONDS = 5L

    /**
     * @return a directly streamable URL that has been proven to serve bytes,
     *   or throws with a reason worth showing.
     *
     * Results are held briefly — see [recent]. Resolving is the slow part of
     * starting a track, and ExoPlayer asks again for every re-open: each seek
     * outside the buffer, and each range the cache fills in.
     */
    suspend fun resolve(videoId: String): String {
        init
        // The first playback request is the event that pays the cold warm-up.
        InnerTubeXResolver.ensureWarm()

        val cached = recent[videoId]
        if (cached != null && MonotonicClock.nowMs() - cached.at < URL_TTL_MS) {
            // A URL minted through the proxy is only playable while the proxy
            // route is still usable *and* is the one that minted it. If the
            // proxy was switched off, past its health window, the scope
            // changed back, or the route moved to the backup (or a new
            // configuration), forget it and let the ordinary path resolve a
            // fresh URL instead. Reusing the old one is a 403: googlevideo
            // binds it to the address that answered the player request.
            if (ProxyPlaybackPolicy.cachedUrlUsable(
                    proxyGeneration = cached.proxyGeneration,
                    currentGeneration = ProxyPlayback.currentRouteGeneration,
                    canResolveViaProxy = ProxyPlayback.canUseCachedProxyUrl(),
                    allScopeNow = ProxyPlaybackPolicy.allScopeNow(
                        configUsable = com.music.bitchord.data.net.YouTubeProxy.config.usable,
                        allScope = com.music.bitchord.data.net.YouTubeProxy.config.scope ==
                            com.music.bitchord.data.net.ProxyScope.ALL_YOUTUBE,
                        bypassing = ProxyHealthMonitor.shouldBypassProxy(),
                    ),
                    mintedOnAllScope = cached.mintedOnAllScope,
                )
            ) {
                return cached.url
            }
            recent.remove(videoId)
        }

        // A verdict, not a failure: asking again cannot change the answer, so
        // every caller after the first is told so without a request being sent.
        // See [rememberUnplayable] for why this is the fix for "stuck loading".
        unplayableReason(videoId)?.let { throw PermanentlyUnplayableException(it) }

        // The walk owns the route stamp. It is computed once inside the shared
        // walk rather than here: a caller that joined an in-flight walk used to
        // capture the route *after* the URL had already been minted, and a
        // route/scope change during that walk then labelled the old URL with
        // the new route, which the next open fetched through the wrong server
        // (403). See [coalescedResolve].
        val walked = coalescedResolve(videoId)
        val stream = walked.stream

        // The container carries no bitrate field, so this is the only place the
        // real figure is ever known.
        NerdStats.onStreamPicked(videoId, stream.kbps)
        stream.loudnessDb?.let { loudness[videoId] = it }
        remember(videoId, stream.url, walked.generation, walked.onAllScope)
        return stream.url
    }

    /** Per-track loudness, read once and kept for as long as the process runs. */
    private val loudness = ConcurrentHashMap<String, Double>()

    /**
     * YouTube's own normalization figure for [videoId], or null when it has
     * never resolved or never carried one.
     *
     * Populated by [resolve] the first time a track's stream is asked for —
     * which happens for every YouTube-queued track whether or not another
     * source ends up serving its bytes, since the YouTube walk always runs
     * alongside a substitute lookup rather than only when one fails. So a
     * track substituted to JioSaavn or an addon still carries the figure its
     * YouTube counterpart resolved.
     */
    fun loudnessDbFor(videoId: String): Double? = loudness[videoId]

    /**
     * A track this app cannot play, for a reason that will read the same in ten
     * seconds — an age gate no session gets past, a takedown, a region block.
     *
     * Its own type because everything above the resolver has to be able to tell
     * it apart from a failure worth retrying, and the layers in between are
     * ExoPlayer's: a load error carries whatever exception it was given and
     * nothing else, so the distinction has to travel in the type. See
     * [PlaybackService][com.music.bitchord.playback.PlaybackService]'s load-error
     * policy and `recoverFrom`.
     */
    class PermanentlyUnplayableException(reason: String) : IOException(reason)

    /**
     * YouTube's "sign in to prove you are not a bot" challenge.
     *
     * A separate type from [PermanentlyUnplayableException] on purpose: the
     * challenge belongs to the network, not to the recording, so playback must
     * stop and report it instead of treating the track as gone and walking the
     * queue. The message is still the listener-facing reason.
     */
    class BotChallengeException(reason: String) : IOException(reason)

    /**
     * Tracks that have already failed for a reason retrying cannot fix, and
     * until when.
     *
     * This is the single change that turns the observed failure — a track that
     * sits in BUFFERING for minutes on end, hammering youtubei — back into a
     * failure that happens once. Nothing above this object retries *less* than
     * three deep: ExoPlayer's own load-error policy retries the source, this
     * service's `recoverFrom` retries the player, and read-ahead resolves the
     * same track again on its own schedule. Against a permanent refusal every
     * one of those is a full client walk plus a triple extraction — measured in
     * the report at roughly twenty-seven walks and fifty youtubei requests in a
     * 2m41s window, for a track whose answer was settled by the first one.
     *
     * Entries expire rather than being permanent, because the reasons behind
     * them do: an age gate stops mattering the moment the listener signs in
     * (see [forgetUnplayable], called from the login flow), and Google's region
     * verdicts are measured in hours, not sessions. A bot challenge is
     * deliberately *not* stored here at all — it belongs to the network, not
     * the track, and is reported through [BotChallengeException] instead. Ten
     * minutes is the same budget [STAND_DOWN_MS] uses, for the same reason —
     * long enough that the storm cannot re-form, short enough that a listener
     * who fixes the cause does not have to restart the app.
     */
    private val unplayable = ConcurrentHashMap<String, Verdict>()

    private class Verdict(val reason: String, val at: Long)

    /**
     * When the visitor id was last refreshed for a bot challenge.
     *
     * A burned visitor id is worth replacing exactly once per window: every
     * track doing it would spend a network round trip on an answer that is
     * usually still the same, and the next challenge is better served by the
     * fresh id than by another refresh. The window is the same as the
     * unplayable verdict's, for the same reason — long enough to break a
     * storm, short enough that a listener who changes network does not have to
     * restart.
     */
    @Volatile
    private var lastBotRefreshAtMs = 0L

    private const val BOT_REFRESH_COOLDOWN_MS = 10 * 60 * 1000L

    private const val BOT_CHALLENGE_REASON = "YouTube가 이 네트워크에서 봇 확인을 요구합니다"

    /**
     * Records a network-wide bot challenge and refreshes the burned visitor id
     * at most once per [BOT_REFRESH_COOLDOWN_MS].
     *
     * Deliberately does not touch [unplayable]: a challenge says nothing about
     * this videoId, and remembering it per track is what used to turn one
     * network refusal into a queue-wide skip.
     */
    private fun noteBotChallenge(videoId: String) {
        val now = System.currentTimeMillis()
        val refresh = synchronized(this) {
            if (now - lastBotRefreshAtMs >= BOT_REFRESH_COOLDOWN_MS) {
                lastBotRefreshAtMs = now
                true
            } else {
                false
            }
        }
        TrackLog.w(
            TAG,
            "$videoId hit YouTube's bot check; this is the network, not the track",
            about = videoId,
        )
        if (refresh) {
            // Off the failure path: the listener is waiting for the error, not
            // for a replacement identity. The next resolve will use it if it
            // lands in time, and a failure here is only a log line.
            resolverScope.launch {
                runCatching { Innertube.ensureVisitorData(refresh = true) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        TrackLog.w(TAG, "could not refresh the visitor id after a bot check: ${it.message}")
                    }
            }
        }
    }

    fun unplayableReason(videoId: String): String? {
        val entry = unplayable[videoId] ?: return null
        if (MonotonicClock.nowMs() - entry.at < UNPLAYABLE_TTL_MS) return entry.reason
        unplayable.remove(videoId)
        return null
    }

    private fun rememberUnplayable(videoId: String, reason: String) {
        if (unplayable.size > MAX_REMEMBERED) unplayable.clear()
        unplayable[videoId] = Verdict(reason, MonotonicClock.nowMs())
    }

    /**
     * Whether [videoId] currently has a verdict saying no client can serve it.
     *
     * Read by the alternate-version search so a candidate that will be refused
     * the moment playback asks for it is not offered as a rescue.
     */
    fun isUnplayable(videoId: String): Boolean = unplayableReason(videoId) != null

    /**
     * Records that [videoId] cannot be played, for callers that have already
     * established the answer (the playback failure path). The same cache
     * [resolve] consults, so read-ahead and the next play stop asking too.
     */
    fun markUnplayable(videoId: String, reason: String) {
        rememberUnplayable(videoId, reason)
    }

    /**
     * Forget every verdict recorded above.
     *
     * Signing in is the one event that can turn an age-gated track playable,
     * and signing out the one that can turn it back — so both have to clear
     * this, or the listener who signs in specifically to play a track is told
     * for the next ten minutes that it still cannot be played. InnerTubeX's
     * exclusions go with it: a client refused while anonymous is owed a fresh
     * hearing now that there is a session to send.
     */
    fun onSessionChanged() {
        InnerTubeXResolver.onSessionChanged()
        unplayable.clear()
        UploadedTracks.clear()
        // The remembered URLs were minted for the identity that just left: a
        // signed-in walk and an anonymous one are not handed the same streams.
        // Kept, one would play the old session's URL for up to [URL_TTL_MS].
        recent.clear()
        lastRefused.clear()
    }

    private const val UNPLAYABLE_TTL_MS = 10 * 60 * 1000L

    /**
     * One walk per videoId at a time.
     *
     * [AudioCache]'s read-ahead resolves the queued track before it is
     * reached, to warm the cache; if the queue advances faster than that
     * walk finishes, playback calls [resolve] for the same track a second
     * time before the first walk has populated [recent]. Left alone, that is
     * two full client walks in flight for the same track at once, each
     * paying for the other's requests — round trips measured elsewhere in
     * this file at ~250ms stretched past 3s under exactly this contention.
     * A second caller for a videoId already being resolved waits on the
     * first walk instead of starting its own.
     *
     * Parented to [resolverScope] rather than the caller's own coroutine, so
     * that a caller giving up on its own timeout — see
     * [PlaybackService][com.music.bitchord.playback.PlaybackService] —
     * cancels only its own wait, not the walk a second caller may still be
     * waiting on. Being parented elsewhere is also why the walk has to be told
     * whose it is — [TrackLog.about] — rather than inheriting it: this is the
     * single largest producer of lines in the log, and every one of them was
     * previously filed against whatever happened to be playing while the walk
     * ran, which for read-ahead is the track before this one.
     */    private suspend fun coalescedResolve(videoId: String): ResolvedWalk {
        inFlight[videoId]?.let { return it.await() }
        // Started lazily so that losing the race below costs nothing: the walk
        // that gets discarded has not run a line, so cancelling it fires no
        // requests and leaves no half-finished deferred behind.
        val walk = resolverScope.async(TrackLog.about(videoId), start = CoroutineStart.LAZY) {
            // Captured once, at the walk's start, so every caller that joins
            // this walk shares the same stamp. A joiner capturing its own after
            // `await` would label a URL minted under the old route with the new
            // one and the next open would fetch it through a server that never
            // issued it (403).
            val mintedGeneration = YouTubeProxy.routeGeneration
            val allScopeAtStart = allScopeInForce()
            val stream = resolveUncached(videoId)
            // Whether ALL was still in force when the URL was actually
            // obtained. A proxy outage that begins mid-walk sends the failsafe
            // down the direct route; stamping that URL as proxy-minted made it
            // get fetched through the proxy once the bypass expired (403). Both
            // ends are required for the same reason as the reverse switch: a URL
            // minted before ALL came on was minted directly.
            ResolvedWalk(
                stream = stream,
                generation = mintedGeneration,
                onAllScope = ProxyPlaybackPolicy.mintedOnAllScopeAfterWalk(
                    allScopeAtStart = allScopeAtStart,
                    allScopeAtEnd = allScopeInForce(),
                ),
            )
        }
        val running = inFlight.putIfAbsent(videoId, walk)
        if (running != null) {
            walk.cancel()
            return running.await()
        }
        // Unregistered by the walk's own completion rather than by the awaiter,
        // which is the difference between coalescing and only appearing to.
        //
        // This used to be `try { deferred.await() } finally { inFlight.remove }`,
        // and the finally is the bug: the walk is parented to [resolverScope]
        // precisely so a caller giving up does not kill it, so a caller that
        // gives up — which is every read-ahead resolve the queue moves past, and
        // every [PlaybackService] resolve that hits its own timeout — took the
        // still-running walk out of the map on its way out. The next caller then
        // found nothing in flight and started a second full walk against the
        // same videoId, which is exactly what the logs show: two overlapping
        // resolves for one track, 4472ms and 4620ms, 2.8s of them concurrent,
        // each paying for the other's round trips. The doc comment above
        // promised one walk per videoId and the finally guaranteed the opposite.
        walk.invokeOnCompletion { inFlight.remove(videoId, walk) }
        walk.start()
        return walk.await()
    }

    private val inFlight = ConcurrentHashMap<String, Deferred<ResolvedWalk>>()

    /**
     * A finished walk and the route stamp every caller of it must share.
     *
     * The stamp travels with the walk because it describes the state when the
     * URL was obtained, not the state each caller happens to observe after
     * waiting for it.
     */
    private class ResolvedWalk(
        val stream: Stream,
        val generation: Long,
        val onAllScope: Boolean,
    )

    /** A direct-built stream and the client that minted it; [client] is null for an InnerTubeX result. */
    private class DirectRun(val stream: Stream, val client: String?)

    /** Whether ALL_YOUTUBE is actually in force now — not merely selected. */
    private fun allScopeInForce(): Boolean = ProxyPlaybackPolicy.allScopeNow(
        configUsable = com.music.bitchord.data.net.YouTubeProxy.config.usable,
        allScope = com.music.bitchord.data.net.YouTubeProxy.config.scope ==
            com.music.bitchord.data.net.ProxyScope.ALL_YOUTUBE,
        bypassing = ProxyHealthMonitor.shouldBypassProxy(),
    )

    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * What the last few starts learned about which path answers here: the
     * client that served the previous track is tried first, and the anonymous
     * direct path sits out once it has been refused five times in six.
     */
    private val order = ResolutionOrder()

    /** Hands [order] the phone's preferences so a learned winner survives a restart for a day. */
    fun initLearning(store: ResolutionOrder.Store) {
        order.attach(store)
    }

    private suspend fun resolveUncached(videoId: String): Stream {
        val resolveStart = MonotonicClock.nowMs()
        // A track that has already played through the proxy is known to be
        // refused here, so asking the direct clients again only spends the
        // eleven seconds this memory exists to save. A proxy miss falls
        // through to the ordinary ladder rather than failing the track.
        var proxyAttempted = false
        if (ProxyPlayback.canResolveViaProxy() && ProxyPlayback.isRemembered(videoId)) {
            proxyAttempted = true
            TrackLog.d(TAG, "proxy-playback: $videoId remembered")
            try {
                return resolveViaProxy(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // resolveViaProxy has already logged the reason.
            }
        }

        // In Korea without Premium the clients InnerTubeX leads with are the
        // refused ones; the anonymous IOS/ANDROID request is what plays there,
        // so it goes first once the gate is known, and last for everyone else.
        // Through the overseas proxy only metadata travels; player requests stay
        // on the Korean connection (see ProxyScopeMatcher), where the Music
        // clients are refused just as when gated, so the same order applies.
        val gated = MusicGateFallback.isActive() || com.music.bitchord.data.net.YouTubeProxy.config.usable

        // The path that served the last track goes first — see [ResolutionOrder].
        // A winner that has missed twice in a row has already been forgotten, and
        // a benched direct path is not a winner worth leading with either.
        val learned = order.preferred()
        val preferredItx = ResolutionOrder.itxManifest(learned)
        val preferDirect = learned == ResolutionOrder.DIRECT
        val orderLabel = if (learned != null) "learned" else "default"

        var servedKey: String? = null
        var servedLabel: String? = null
        fun served(key: String, label: String) {
            servedKey = key
            servedLabel = label
        }

        var itxWalked = false
        var directTried = false

        // The direct path's answer, in the one form the proxy retry cares
        // about: a client said UNPLAYABLE/LOGIN_REQUIRED rather than merely
        // being slow. Read by the failure handler below.
        var directUnplayable = false
        var directUnplayableReason: String? = null
        var itxUnplayableReason: String? = null

        // A claim, a takedown or a private upload names the recording itself.
        // A region/Premium gate, an age-restricted or a paid track is not gone:
        // those still have to reach the proxy retry and extraction. Only the
        // definitely-gone set may settle the resolve early; isTrackSpecific is
        // still used below for bench decisions, where age/paid count too.
        fun goneReason(): String? =
            directUnplayableReason?.takeIf { UnplayableReason.isDefinitelyGone(it) }
                ?: itxUnplayableReason?.takeIf { UnplayableReason.isDefinitelyGone(it) }

        fun goneRefusal(): Boolean = goneReason() != null

        // When InnerTubeX leads (a learned InnerTubeX path, or the default
        // non-gated ladder), its walk can take thirteen seconds on a track the
        // sub-second direct ask can already pronounce gone. The direct probe
        // therefore starts beside it; only a definitive recording verdict may
        // cancel the walk. See [raceDirectDefinitive].
        val itxLeads = preferredItx != null || (!preferDirect && !gated)
        var directPrecheck: Deferred<DirectProbe<DirectRun>>? =
            if (itxLeads && order.directAllowed()) {
                resolverScope.async(TrackLog.about(videoId)) {
                    timed("$videoId direct") {
                        directProbe(videoId, checkActive = { ensureActive() })
                    }
                }
            } else {
                null
            }

        suspend fun tryDirect(): Stream? {
            if (directTried) return null
            directTried = true
            if (!order.directAllowed()) {
                // Five refusals in the last six tracks: each one costs about
                // 400ms before InnerTubeX even starts, so stop paying it until
                // the bench expires or the process restarts.
                directPrecheck?.cancel()
                TrackLog.d(TAG, "resolve: direct path is benched, skipping it for $videoId")
                return null
            }
            val probe = try {
                directPrecheck?.also { directPrecheck = null }?.await()
                    ?: timed("$videoId direct") { directProbe(videoId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                TrackLog.w(TAG, "direct probe failed for $videoId: ${e.javaClass.simpleName}: ${e.message}")
                DirectProbe.Other(refused = false, reason = null)
            }
            if (probe is DirectProbe.Stream) {
                served(ResolutionOrder.DIRECT, "direct ${probe.value.client}")
                order.noteDirectServed()
                return probe.value.stream
            }
            if (probe is DirectProbe.Gone) {
                directUnplayable = true
                directUnplayableReason =
                    UnplayableReason.keepTrackSpecific(directUnplayableReason, probe.reason)
            } else if (probe is DirectProbe.Other) {
                if (probe.refused) directUnplayable = true
                directUnplayableReason =
                    UnplayableReason.keepTrackSpecific(directUnplayableReason, probe.reason)
            }
            // A takedown, a claim or a deleted upload is a fact about this
            // recording, not about the direct path. Counting it would bench
            // that path for the next, perfectly playable track — the report
            // shows exactly that: one blocked song and the following start
            // lost its learned order.
            if (!UnplayableReason.isTrackSpecific(directUnplayableReason)) {
                order.noteDirectRefused()
                order.noteRefused(ResolutionOrder.DIRECT)
            }
            return null
        }

        suspend fun tryInnerTubeX(preferredManifest: String?): Stream? {
            if (itxWalked) return null
            itxWalked = true
            val found = timed("$videoId InnerTubeX") {
                innerTubeXStreamAllowingUploads(
                    videoId,
                    preferredManifest = preferredManifest,
                    onServed = { key, label -> served(key, label) },
                    onFailure = {
                        itxUnplayableReason = UnplayableReason.keepTrackSpecific(itxUnplayableReason, it)
                    },
                )
            }
            // A claim or a deleted upload says nothing about the learned path:
            // benching it would make the next, playable track start on the
            // slower default order for a refusal that was never its fault.
            if (found == null && preferredManifest != null &&
                !UnplayableReason.isTrackSpecific(itxUnplayableReason)
            ) {
                order.noteRefused(ResolutionOrder.itx(preferredManifest))
            }
            return found
        }

        suspend fun tryInnerTubeXWatchingDirect(preferredManifest: String?): Stream? {
            val precheck = directPrecheck ?: return tryInnerTubeX(preferredManifest)
            val itx = resolverScope.async(TrackLog.about(videoId)) {
                tryInnerTubeX(preferredManifest)?.let { DirectRun(it, client = null) }
            }
            // A learned InnerTubeX path keeps its lead, but not for the whole
            // thirteen-second walk when the direct client has already said the
            // request is refused. The reported start waited 9 s here before the
            // proxy was even asked; the direct answer had been in hand since
            // 269 ms. The old truth table is unchanged for every other answer:
            // a stream or an ordinary slow probe still lets InnerTubeX lead.
            val raced = try {
                withTimeoutOrNull(BlockedStartPolicy.DIRECT_HOLD_MS) {
                    raceDirectDefinitive(precheck, itx)
                }
            } catch (cancelled: CancellationException) {
                itx.cancel()
                throw cancelled
            }
            if (raced != null) {
                return when (raced) {
                    is DirectVerdictRace.Gone -> {
                        directUnplayable = true
                        directUnplayableReason =
                            UnplayableReason.keepTrackSpecific(directUnplayableReason, raced.reason)
                        TrackLog.d(
                            TAG,
                            "resolve: $videoId definitive by direct in ${MonotonicClock.nowMs() - resolveStart}ms",
                        )
                        throw PermanentlyUnplayableException(raced.reason)
                    }
                    is DirectVerdictRace.ViaInnerTubeX -> raced.value.stream
                    DirectVerdictRace.None -> null
                }
            }
            val directRun = if (precheck.isCompleted) runCatching { precheck.getCompleted() }.getOrNull() else null
            if (directRun is DirectProbe.Gone) {
                itx.cancel()
                directUnplayable = true
                directUnplayableReason =
                    UnplayableReason.keepTrackSpecific(directUnplayableReason, directRun.reason)
                throw PermanentlyUnplayableException(directRun.reason)
            }
            val directRefused = directRun is DirectProbe.Other && directRun.refused
            if (directRun is DirectProbe.Other) {
                directUnplayable = directRun.refused
                directUnplayableReason =
                    UnplayableReason.keepTrackSpecific(directUnplayableReason, directRun.reason)
            }
            val elapsed = MonotonicClock.nowMs() - resolveStart
            if (BlockedStartPolicy.shouldStartProxyEarly(
                    directRefused = directRefused,
                    innerTubeXStillRunning = !itx.isCompleted,
                    elapsedMs = elapsed,
                ) && ProxyPlayback.canResolveViaProxy() && !proxyAttempted
            ) {
                proxyAttempted = true
                TrackLog.d(
                    TAG,
                    BlockedStartPolicy.logLine(videoId, BlockedStartStep.DIRECT, "refused", elapsed),
                )
                TrackLog.i(
                    TAG,
                    BlockedStartPolicy.logLine(videoId, BlockedStartStep.PROXY, "start", elapsed),
                )
                val proxyTimeout = BlockedStartPolicy.timeoutFor(BlockedStartStep.PROXY, elapsed)
                val itxStream = resolverScope.async(TrackLog.about(videoId)) { itx.await()?.stream }
                // The whole race is bounded, not just the proxy leg. A proxy
                // failure that landed first used to leave the race waiting on
                // the thirteen-second InnerTubeX walk, which is the same
                // silence in a different coroutine.
                //
                // The proxy's own exception is kept rather than collapsed into
                // the race's `null`: a dead proxy is a network fault, not a
                // verdict about this recording. Losing it here made the block
                // below throw [PermanentlyUnplayableException], which the outer
                // handler remembers for ten minutes and which skipped
                // extraction — the exact guard [ProxyFailure.isProxyDown]
                // exists to keep transient (see the proxy retry below).
                var proxyFailure: Throwable? = null
                val racedPair = try {
                    withTimeoutOrNull(proxyTimeout) {
                        raceForFirstSuccess(
                            direct = { itxStream.await() },
                            proxy = {
                                try {
                                    withTimeoutOrNull(proxyTimeout) { resolveViaProxy(videoId) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    proxyFailure = failure
                                    null
                                }
                            },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    itxStream.cancel()
                    itx.cancel()
                    throw cancelled
                }
                val winner = racedPair?.first
                val won = racedPair?.second
                if (winner == "proxy" || racedPair == null) {
                    itxStream.cancel()
                    itx.cancel()
                }
                TrackLog.i(
                    TAG,
                    BlockedStartPolicy.logLine(
                        videoId,
                        BlockedStartStep.PROXY,
                        if (won != null) "ok:$winner" else "failed",
                        MonotonicClock.nowMs() - resolveStart,
                    ),
                )
                if (won != null) return won
                val deadProxy = proxyFailure
                if (deadProxy != null && ProxyFailure.isProxyDown(deadProxy)) {
                    TrackLog.w(
                        TAG,
                        "blocked-start: $videoId step=proxy result=error ms=" +
                            "${MonotonicClock.nowMs() - resolveStart}; " +
                            "proxy route failed, keeping the track transient",
                    )
                    itxStream.cancel()
                    itx.cancel()
                    throw deadProxy
                }
                if (BlockedStartPolicy.shouldSkipExtraction(
                        directRefused = true,
                        proxyFailed = true,
                    )
                ) {
                    // Extraction reads the same watch page and answers the same
                    // gate; walking it here is the rest of the reported minute.
                    TrackLog.w(
                        TAG,
                        "blocked-start: $videoId direct and proxy both refused; " +
                            "not spending extraction",
                    )
                    throw PermanentlyUnplayableException(
                        directUnplayableReason ?: itxUnplayableReason
                            ?: "이 국가에서는 이용할 수 없는 트랙입니다",
                    )
                }
            }
            // No early refusal, or no proxy to ask: preserve the old race
            // exactly. The direct result is already complete, so this costs no
            // second request.
            val outcome = try {
                raceDirectDefinitive(precheck, itx)
            } catch (cancelled: CancellationException) {
                itx.cancel()
                throw cancelled
            }
            return when (outcome) {
                is DirectVerdictRace.Gone -> {
                    directUnplayable = true
                    directUnplayableReason =
                        UnplayableReason.keepTrackSpecific(directUnplayableReason, outcome.reason)
                    TrackLog.d(
                        TAG,
                        "resolve: $videoId definitive by direct in ${MonotonicClock.nowMs() - resolveStart}ms",
                    )
                    throw PermanentlyUnplayableException(outcome.reason)
                }
                is DirectVerdictRace.ViaInnerTubeX -> outcome.value.stream
                DirectVerdictRace.None -> null
            }
        }

        try {
        val stream = try {
            // The first ask is the cheap one (the learned or preferred route,
            // ~0.3 s). Most tracks are served right there and never involve the
            // proxy; racing every track would double the player requests and
            // send playable tracks through Tokyo.
            val first = when {
                preferredItx != null -> tryInnerTubeXWatchingDirect(preferredItx)
                preferDirect -> tryDirect()
                else -> null
            }
            if (first == null && goneRefusal()) {
                // The recording itself is gone (claim, takedown, private or
                // deleted). Extraction and a proxy walk answer the same on
                // every client, so settle it once instead of spending the
                // retry budget on an answer already in hand.
                throw PermanentlyUnplayableException(
                    goneReason() ?: "이 트랙은 재생할 수 없습니다",
                )
            }
            // Parenthesised so the extraction failsafe below applies to every
            // branch, the race included, not only to the last `else`.
            (if (first != null) {
                first
            } else if (!goneRefusal() &&
                (preferredItx != null || preferDirect) &&
                ProxyPlayback.canResolveViaProxy() && !proxyAttempted
            ) {
                // The first ask was refused (with no first ask there is nothing
                // to read yet, and the ordinary ladder plus the refusal handler
                // below still route a gated track to the proxy). A Korea-gated track would now spend
                // five or more seconds on the rest of the direct ladder before
                // the proxy got its turn (Sorry, 9.4 s to first sound), so the
                // proxy starts beside the rest of it and the first stream wins.
                proxyAttempted = true
                TrackLog.i(
                    TAG,
                    "proxy-playback: $videoId early race (first refusal at ${MonotonicClock.nowMs() - resolveStart}ms)",
                )
                val (winner, won) = raceForFirstSuccess(
                    direct = {
                        if (gated) tryDirect() ?: tryInnerTubeX(null) else tryInnerTubeX(null) ?: tryDirect()
                    },
                    proxy = {
                        try {
                            resolveViaProxy(videoId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            null
                        }
                    },
                )
                TrackLog.i(
                    TAG,
                    "proxy-playback: $videoId race won by $winner in ${MonotonicClock.nowMs() - resolveStart}ms",
                )
                won
            } else if (gated) {
                // With no learned route the gated ladder asks the anonymous
                // IOS/ANDROID player first (~0.3 s). When it answers UNPLAYABLE
                // that is the first refusal: Blue Valentine then spent 10 s on
                // InnerTubeX before the proxy was tried (14.3 s to first
                // sound), so the proxy starts beside InnerTubeX from here.
                tryDirect() ?: if (goneRefusal()) {
                    null
                } else if (directUnplayable &&
                    ProxyPlayback.canResolveViaProxy() && !proxyAttempted
                ) {
                    proxyAttempted = true
                    TrackLog.i(
                        TAG,
                        "proxy-playback: $videoId early race (direct refused at ${MonotonicClock.nowMs() - resolveStart}ms)",
                    )
                    val (winner, won) = raceForFirstSuccess(
                        direct = { tryInnerTubeX(null) },
                        proxy = {
                            try {
                                resolveViaProxy(videoId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (e: Exception) {
                                null
                            }
                        },
                    )
                    TrackLog.i(
                        TAG,
                        "proxy-playback: $videoId race won by $winner in ${MonotonicClock.nowMs() - resolveStart}ms",
                    )
                    won
                } else {
                    tryInnerTubeX(null)
                }
            } else {
                tryInnerTubeXWatchingDirect(null) ?: tryDirect()
            }) ?: if (goneRefusal()) {
                // The direct ladder may have been the one to name the claim or
                // the deletion. Extraction reads the same watch page and would
                // spend its attempts repeating that answer.
                throw PermanentlyUnplayableException(
                    goneReason() ?: "이 트랙은 재생할 수 없습니다",
                )
            } else run {
                TrackLog.w(TAG, "InnerTubeX found no usable stream for $videoId; falling back to extraction")
                timed("$videoId newPipeStream") { newPipeStream(videoId, ::pickForQuality) }
                    .also { served(ResolutionOrder.NEW_PIPE, "NewPipe") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            // Every strategy above either runs third-party extraction code or
            // drives YouTube's player JavaScript, so any of them can turn out to
            // have been compiled against an API this OS version does not carry.
            // That arrives as an Error, which the clause below does not catch and
            // no caller of this function catches either — ExoPlayer's loader
            // thread least of all, which is where it surfaced as a process kill
            // rather than a failed track. Converted here, at the one point every
            // strategy passes through, so the answer is the same wherever the
            // linkage failure came from.
            TrackLog.w(
                TAG,
                "resolve hit a linkage failure for $videoId after " +
                    "${MonotonicClock.nowMs() - resolveStart}ms: ${e.javaClass.name}: ${e.message}",
                e,
            )
            throw IOException("Stream resolution cannot run on this device: $e", e)
        } catch (e: Exception) {
            // The one path out of here that said nothing at all. A resolve that
            // throws is handed to ExoPlayer as a load error, which retries it on
            // a backoff of its own — so the symptom is a track that sits in
            // BUFFERING and walks the clients again every thirty seconds, with
            // no line anywhere naming what actually went wrong. The stack trace
            // is the point: the failure is usually several frames inside
            // NewPipe, where the message alone ("null", commonly) identifies
            // nothing.
            TrackLog.w(
                TAG,
                "resolve failed for $videoId after ${MonotonicClock.nowMs() - resolveStart}ms: " +
                    "${e.javaClass.name}: ${e.message}",
                e,
            )
            // A direct refusal that reads like the Korean-IP gate gets exactly
            // one more walk, with this single player request pinned to the
            // proxy. The player response's stream URL is then bound to the
            // proxy's address, which is why [ProxyPlayback] remembers the URL
            // and the media fetch follows it there.
            if (!proxyAttempted &&
                !goneRefusal() &&
                !UnplayableReason.isDefinitelyGone(permanentReason(e)) &&
                ProxyPlayback.canResolveViaProxy() &&
                ProxyPlaybackPolicy.isRegionRefusal(
                    signInConfirmNotBot = e.isSignInConfirmNotBot(),
                    directUnplayable = directUnplayable,
                )
            ) {
                proxyAttempted = true
                TrackLog.i(TAG, "proxy-playback: $videoId refused direct -> via proxy")
                try {
                    return resolveViaProxy(videoId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (proxyFailure: Exception) {
                    // A dead proxy is a network fault, not a verdict about the
                    // track: undo the health window and let the usual retry
                    // path try the direct route again once it has cleared.
                    if (ProxyFailure.isProxyDown(proxyFailure)) throw proxyFailure
                    if (proxyFailure.isSignInConfirmNotBot()) {
                        noteBotChallenge(videoId)
                        throw BotChallengeException(BOT_CHALLENGE_REASON)
                    }
                    val reason = permanentReason(proxyFailure)
                        ?: "이 국가에서는 이용할 수 없는 트랙입니다"
                    rememberUnplayable(videoId, reason)
                    TrackLog.w(TAG, "$videoId is not playable: $reason; not asking again for 10 minutes")
                    throw PermanentlyUnplayableException(reason)
                }
            }
            // The bot challenge is a fact about this network, not this
            // recording. Remembering it per videoId made every queued track
            // look broken and the player skip through them; refreshing the
            // visitor id once gives the next request a chance with an identity
            // that has not been accused.
            if (e.isSignInConfirmNotBot()) {
                noteBotChallenge(videoId)
                throw BotChallengeException(BOT_CHALLENGE_REASON)
            }
            // Recorded before it is rethrown, so the retries stacked above this
            // — ExoPlayer's, the service's, read-ahead's — are answered from
            // memory instead of each one asking InnerTubeX and extracting three
            // times against a refusal that is never going to soften.
            permanentReason(e)?.let { reason ->
                rememberUnplayable(videoId, reason)
                TrackLog.w(TAG, "$videoId is not playable: $reason; not asking again for 10 minutes")
                throw PermanentlyUnplayableException(reason)
            }
            throw e
        }
        val took = MonotonicClock.nowMs() - resolveStart
        // Learn only paths that are cheap enough to lead with. Extraction is
        // the failsafe: it is slow by design and must not become the default
        // because it happened to answer once.
        servedKey?.takeIf { it != ResolutionOrder.NEW_PIPE }?.let(order::noteServed)
        TrackLog.d(
            TAG,
            "resolve: $videoId via ${servedLabel ?: "unknown"} in $took ms (order=$orderLabel)",
        )
        TrackLog.d(TAG, "TIMING $videoId total resolve: $took ms")
        ResolutionFacts.note(videoId, "direct")
        return stream
        } finally {
            // The precheck belongs to this walk only. If an early exit — a
            // vanished recording, an extraction failsafe or a caller's
            // cancellation — leaves it unconsumed, stop its HTTP/probe work
            // rather than letting it finish out on resolverScope.
            directPrecheck?.cancel()
        }
    }

    /**
     * One more walk for [videoId], with the player ask and the media probe
     * pinned to the proxy.
     *
     * The caller has already established that the direct route was refused
     * in the Korean-IP pattern, and that a proxy is configured, usable and not
     * in its unreachable window. This method makes no such decision again: a
     * failure here is either handed back as a network fault or recorded as a
     * verdict by the caller.
     */
    private suspend fun resolveViaProxy(
        videoId: String,
        maxKbps: Int = maxKbps(),
        requireM4a: Boolean = false,
        allowBackupRetry: Boolean = true,
    ): Stream {
        val start = MonotonicClock.nowMs()
        // The route this request is actually sent on. The answer is stamped
        // with it below even if a health switch has moved the current route
        // by the time the bytes URL comes back. Stamped before the request, not
        // after it: a browse success can move the route while this player walk
        // is in flight, and stamping at the end would label the URL with a
        // server that never minted it.
        val requestGeneration = ProxyPlayback.currentRouteGeneration
        val found = try {
            timed("$videoId proxy InnerTubeX") {
                innerTubeXStreamAllowingUploads(videoId, maxKbps, requireM4a, viaProxy = true)
            } ?: timed("$videoId proxy direct") {
                directStream(videoId, maxKbps, viaProxy = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "proxy-playback: $videoId failed: ${e.javaClass.simpleName}: ${e.message}")
            if (ProxyFailure.isProxyDown(e)) {
                if (!allowBackupRetry) {
                    // This walk already used the backup; both are down.
                    ProxyHealthMonitor.recordBackupUnreachable()
                } else {
                    // A backup configured is asked once for this same track;
                    // the switch itself happens in the health monitor.
                    ProxyHealthMonitor.recordUnreachable("stream $videoId")
                    if (YouTubeProxy.usingBackup && !ProxyHealthMonitor.isMarkedUnreachable()) {
                        TrackLog.i(TAG, "proxy-playback: $videoId retrying through the backup proxy")
                        return resolveViaProxy(videoId, allowBackupRetry = false)
                    }
                }
            }
            throw e
        }
        if (found == null) {
            TrackLog.w(TAG, "proxy-playback: $videoId failed: no stream")
            throw IOException("stream resolution found no stream for $videoId")
        }
        ProxyHealthMonitor.recordSuccess(allowRouteChange = false)
        val first = ProxyPlayback.markResolved(videoId, found.url, requestGeneration)
        ResolutionFacts.note(videoId, "proxy")
        TrackLog.i(TAG, "proxy-playback: $videoId ok in ${MonotonicClock.nowMs() - start}ms")
        if (first) TrackLog.d(TAG, "proxy-playback: $videoId remembered")
        return found
    }

    /**
     * Whether [this] is NewPipe's "sign in to prove you are not a bot" answer,
     * however many layers wrapped it on the way out.
     */
    private fun Throwable.isSignInConfirmNotBot(): Boolean {
        var current: Throwable? = this
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        while (current != null) {
            if (!seen.add(current)) return false
            if (current is SignInConfirmNotBotException ||
                UnplayableReason.isBotChallenge(current.message)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Whether [this] is a refusal a proxy walk may answer — a country or
     * Premium gate, or the bot challenge — as opposed to a takedown or a
     * transport failure.
     */
    private fun Throwable.isProxyWorthyRefusal(): Boolean {
        var current: Throwable? = this
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        while (current != null) {
            if (!seen.add(current)) return false
            if (current is SignInConfirmNotBotException ||
                current is GeographicRestrictionException ||
                current is UnsupportedContentInCountryException ||
                current is YoutubeMusicPremiumContentException ||
                UnplayableReason.isRegionGate(current.message)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * A probed stream from [InnerTubeXResolver], or null to fall through to extraction.
     *
     * A client whose URL fails the probe is skipped and InnerTubeX asked again, up
     * to [INNERTUBEX_ATTEMPTS] times, since its catalog has further clients behind it.
     */
    private suspend fun innerTubeXStream(
        videoId: String,
        maxKbps: Int = maxKbps(),
        requireM4a: Boolean = false,
        uploaded: Boolean = false,
        escalateToUpload: Boolean = true,
        playRefusedUpload: Boolean = false,
        preferredManifest: String? = null,
        viaProxy: Boolean = false,
        onServed: (pathKey: String, label: String) -> Unit = { _, _ -> },
        onFailure: (String?) -> Unit = {},
    ): Stream? {
        val skip = mutableSetOf<String>()
        // In a gated region WEB_REMIX is the identity that answers UNPLAYABLE
        // for every track. InnerTubeX already walks its own catalog, but
        // excluding the profile by name when it is known saves the dead ask.
        if (MusicGateFallback.isActive() || com.music.bitchord.data.net.YouTubeProxy.config.usable) skip += "WEB_REMIX"
        // Ask the remembered winner on its own first — see [ResolutionOrder].
        // The override makes InnerTubeX use that one client instead of walking
        // its catalog from the top, so the profile that answered last time is
        // no longer tried last. A miss falls through to the normal walk below.
        if (preferredManifest != null) {
            val found = try {
                InnerTubeXResolver.extract(
                    videoId,
                    maxKbps,
                    skip,
                    requireM4a,
                    uploaded,
                    preferredClientId = preferredManifest,
                    viaProxy = viaProxy,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "InnerTubeX $preferredManifest preferred ask failed for $videoId: ${e.javaClass.simpleName}: ${e.message}")
                onFailure(e.message)
                null
            }
            if (found != null) {
                val verdict = timed("$videoId InnerTubeX ${found.profileId} probe") { probe(found.url, viaProxy) }
                if (verdict == Probe.OK) {
                    TrackLog.d(TAG, "resolved $videoId via InnerTubeX ${found.profileId} @ ${found.kbps}kbps")
                    onServed(ResolutionOrder.itx(preferredManifest), "InnerTubeX ${found.profileId}")
                    return Stream(found.url, found.kbps, found.mimeType, found.loudnessDb)
                }
                TrackLog.w(TAG, "InnerTubeX ${found.profileId} minted an unusable URL for $videoId: $verdict")
                if (playRefusedUpload && uploaded && verdict == Probe.REFUSED) {
                    TrackLog.w(TAG, "playing $videoId's upload stream despite the probe: it is the only copy")
                    onServed(ResolutionOrder.itx(preferredManifest), "InnerTubeX ${found.profileId}")
                    return Stream(found.url, found.kbps, found.mimeType, found.loudnessDb)
                }
                skip += found.profileId
            }
        }
        repeat(INNERTUBEX_ATTEMPTS) { attempt ->
            val found = try {
                InnerTubeXResolver.extract(
                    videoId,
                    maxKbps,
                    skip,
                    requireM4a,
                    uploaded,
                    viaProxy = viaProxy,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "InnerTubeX failed for $videoId: ${e.javaClass.simpleName}: ${e.message}")
                onFailure(e.message)
                // A Premium refusal from a web client says this network is
                // gated, not that this track is. The fallback decides the rest
                // of the session from here; the media walk itself keeps the
                // other clients in its own catalog.
                MusicGateFallback.observePremiumFailure(e.message)
                // A takedown, a claim or a deleted upload is not something the
                // extraction failsafe can climb over. Reaching it anyway spent
                // three watch-page attempts on an answer InnerTubeX had
                // already settled. Thrown as-is so [resolveUncached]'s
                // permanent-reason handler records it once.
                if (UnplayableReason.isDefinitelyGone(e.message)) {
                    TrackLog.w(TAG, "InnerTubeX will not succeed for $videoId: ${e.message}")
                    throw e
                }
                if (MusicGateFallback.isActive() && "WEB_REMIX" !in skip) {
                    // One more walk, now with the gated web profile excluded.
                    skip += "WEB_REMIX"
                    return@repeat
                }
                return null
            } ?: return null
            val verdict = timed("$videoId InnerTubeX ${found.profileId} probe") { probe(found.url, viaProxy) }
            if (verdict == Probe.OK) {
                TrackLog.d(TAG, "resolved $videoId via InnerTubeX ${found.profileId} @ ${found.kbps}kbps")
                val manifest = InnerTubeXResolver.manifestIdForProfile(found.profileId)
                if (manifest != null) onServed(ResolutionOrder.itx(manifest), "InnerTubeX ${found.profileId}")
                return Stream(found.url, found.kbps, found.mimeType, found.loudnessDb)
            }
            TrackLog.w(TAG, "InnerTubeX ${found.profileId} minted an unusable URL for $videoId: $verdict")

            if (playRefusedUpload && uploaded && verdict == Probe.REFUSED) {
                TrackLog.w(TAG, "playing $videoId's upload stream despite the probe: it is the only copy")
                val manifest = InnerTubeXResolver.manifestIdForProfile(found.profileId)
                if (manifest != null) onServed(ResolutionOrder.itx(manifest), "InnerTubeX ${found.profileId}")
                return Stream(found.url, found.kbps, found.mimeType, found.loudnessDb)
            }
            skip += found.profileId

            if (escalateToUpload && attempt == 0 && !uploaded &&
                verdict == Probe.REFUSED && !Innertube.cookie.isNullOrBlank()
            ) {
                askAsUpload(videoId, maxKbps, requireM4a, viaProxy, onServed)?.let { return it }
            }
        }
        return null
    }

    /**
     * A probed stream from [DirectClientResolver]'s anonymous IOS/ANDROID
     * request, or null. Each candidate is probed with the same headers the
     * real fetch will send, so a URL that would 403 mid-track is skipped here.
     */
    private fun directStream(
        videoId: String,
        maxKbps: Int = maxKbps(),
        viaProxy: Boolean = false,
        onUnplayable: (String?) -> Unit = {},
        onServed: (client: String) -> Unit = {},
    ): Stream? = when (val probe = directProbe(videoId, maxKbps, viaProxy)) {
        is DirectProbe.Stream -> {
            onServed(probe.value.client.orEmpty())
            probe.value.stream
        }
        is DirectProbe.Gone -> {
            onUnplayable(probe.reason)
            null
        }
        is DirectProbe.Other -> {
            if (probe.refused) onUnplayable(probe.reason)
            null
        }
    }

    /**
     * The unprobed direct answer, kept separate from [directStream] so the
     * learned-InnerTubeX walk can watch it without touching the caller's
     * path-learning state. See [raceDirectDefinitive].
     */
    private fun directProbe(
        videoId: String,
        maxKbps: Int = maxKbps(),
        viaProxy: Boolean = false,
        checkActive: () -> Unit = {},
    ): DirectProbe<DirectRun> {
        var refused = false
        var refusedReason: String? = null
        for (found in DirectClientResolver.candidates(
            videoId,
            maxKbps,
            viaProxy,
            onRefused = { reason ->
                refused = true
                refusedReason = UnplayableReason.keepTrackSpecific(refusedReason, reason)
            },
            checkActive = checkActive,
        )) {
            checkActive()
            if (probe(found.url, viaProxy) == Probe.OK) {
                TrackLog.d(TAG, "resolved $videoId via direct ${found.client} @ ${found.kbps}kbps")
                return DirectProbe.Stream(
                    DirectRun(
                        stream = Stream(found.url, found.kbps, found.mimeType),
                        client = found.client,
                    ),
                )
            }
            TrackLog.w(TAG, "direct ${found.client} URL for $videoId failed the probe")
        }
        // These clients carry no cookie, so "private" may only mean the
        // signed-in account owns it. Let the cookie-backed walk decide instead
        // of cancelling it and caching the track as permanently unplayable.
        val privateForSession = anonymousPrivateMayBeOwned(
            refusedReason,
            mayBeOwnPrivateUpload = UploadedTracks.contains(videoId) ||
                !Innertube.cookie.isNullOrBlank(),
        )
        return when {
            UnplayableReason.isDefinitelyGone(refusedReason) && !privateForSession ->
                DirectProbe.Gone(refusedReason.orEmpty())
            privateForSession ->
                DirectProbe.Other(refused = true, reason = null)
            else ->
                DirectProbe.Other(refused, refusedReason)
        }
    }

    /** One ask for [videoId] as the user's own upload; a hit is remembered in [UploadedTracks]. */
    private suspend fun askAsUpload(
        videoId: String,
        maxKbps: Int,
        requireM4a: Boolean,
        viaProxy: Boolean,
        onServed: (pathKey: String, label: String) -> Unit = { _, _ -> },
    ): Stream? {
        TrackLog.d(TAG, "first stream for $videoId was refused; asking as an upload")
        val found = try {
            InnerTubeXResolver.extract(
                videoId,
                maxKbps,
                emptySet(),
                requireM4a,
                uploaded = true,
                viaProxy = viaProxy,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "asking for $videoId as an upload failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (found == null) {
            TrackLog.w(TAG, "no stream for $videoId when asked as an upload")
            return null
        }
        val verdict = timed("$videoId InnerTubeX ${found.profileId} upload probe") { probe(found.url, viaProxy) }
        if (verdict != Probe.OK) {
            TrackLog.w(TAG, "InnerTubeX ${found.profileId} as an upload for $videoId: $verdict")
            return null
        }
        TrackLog.d(TAG, "resolved $videoId as an upload via InnerTubeX ${found.profileId} @ ${found.kbps}kbps")
        UploadedTracks.mark(videoId)
        InnerTubeXResolver.manifestIdForProfile(found.profileId)
            ?.let { onServed(ResolutionOrder.itx(it), "InnerTubeX ${found.profileId} (upload)") }
        return Stream(found.url, found.kbps, found.mimeType, found.loudnessDb)
    }

    /**
     * [innerTubeXStream], asked as an upload from the start when the track is
     * known to be one. An unknown one is escalated by [innerTubeXStream]
     * itself when its first stream is refused while signed in — asking again
     * here would put up to [INNERTUBEX_ATTEMPTS] more extractions in front of
     * every signed-in failure before the extraction fallback gets a turn.
     */
    private suspend fun innerTubeXStreamAllowingUploads(
        videoId: String,
        maxKbps: Int = maxKbps(),
        requireM4a: Boolean = false,
        preferredManifest: String? = null,
        viaProxy: Boolean = false,
        onServed: (pathKey: String, label: String) -> Unit = { _, _ -> },
        onFailure: (String?) -> Unit = {},
    ): Stream? {
        val known = UploadedTracks.contains(videoId)
        if (known) {
            innerTubeXStream(
                videoId,
                maxKbps,
                requireM4a,
                uploaded = true,
                playRefusedUpload = true,
                preferredManifest = preferredManifest,
                viaProxy = viaProxy,
                onServed = onServed,
                onFailure = onFailure,
            )
                ?.let { return it }
            TrackLog.w(TAG, "$videoId is marked as an upload but did not resolve as one; trying an ordinary ask")
            return innerTubeXStream(
                videoId,
                maxKbps,
                requireM4a,
                uploaded = false,
                escalateToUpload = false,
                preferredManifest = preferredManifest,
                viaProxy = viaProxy,
                onServed = onServed,
                onFailure = onFailure,
            )
        }
        return innerTubeXStream(
            videoId,
            maxKbps,
            requireM4a,
            preferredManifest = preferredManifest,
            viaProxy = viaProxy,
            onServed = onServed,
            onFailure = onFailure,
        )
    }

    /** The headers a media fetch for [url] must carry to match whoever minted it. */
    fun mediaHeadersFor(url: String): Map<String, String> {
        // Only googlevideo URLs can have been minted by InnerTubeX. Checking
        // that first keeps a module's own URL from loading the whole extractor
        // just to ask a map that can never contain it.
        val minted = if (url.toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") == true) {
            InnerTubeXResolver.headersFor(url)
        } else {
            null
        }
        return minted ?: PlayerClient.forStreamUrl(url).mediaHeaders()
    }

    private const val INNERTUBEX_ATTEMPTS = 3

    /** Logs how long [block] took, whatever it returns — a timing probe, not a control flow change. */
    private suspend inline fun <T> timed(label: String, block: suspend () -> T): T {
        val start = MonotonicClock.nowMs()
        return block().also { TrackLog.d(TAG, "TIMING $label: ${MonotonicClock.nowMs() - start}ms") }
    }

    /**
     * A resolved stream: the URL, and what the format behind it turned out to
     * be. Playback only ever needs the URL; a download needs the rest of it to
     * name the file and declare its type.
     */
    class Stream(val url: String, val kbps: Int, val mimeType: String, val loudnessDb: Double? = null) {

        /**
         * The container these bytes are actually in, which is not always what
         * names them.
         *
         * Nothing here transcodes or remuxes — what googlevideo sends is what
         * lands on disk — so the extension has to describe the bytes rather
         * than the codec inside them. YouTube's Opus is Opus-in-WebM, and the
         * two sources disagree about how to say so: the player endpoint calls
         * it `audio/webm; codecs="opus"` and NewPipe calls it `audio/opus`.
         * Taking the latter at face value would write a WebM file named
         * `.opus`, and an `.opus` file is expected to be Ogg — which is how a
         * perfectly good download ends up refusing to open in half the players
         * on the device.
         *
         * App-private downloads can keep the WebM rendition, while exported
         * downloads require MP4. Playback also hands Opus around, and a file an
         * older build already wrote is still a `.webm` this app has to be able
         * to describe.
         */
        val downloadExtension: String
            get() = when {
                "mp4" in mimeType || "m4a" in mimeType -> "m4a"
                else -> "webm"
            }

        /** What the media store should be told this file is. */
        val downloadMimeType: String
            get() = if (downloadExtension == "m4a") "audio/mp4" else "audio/webm"
    }

    /**
     * A URL googlevideo refused, and enough about the rendition it served to
     * ask for the same one again.
     *
     * [url] is null when the failure path only knows the videoId — the item's
     * virtual `bitchord://` URI does not carry the resolved URL.
     */
    class RefusedStream(
        val videoId: String,
        val url: String?,
        val mimeType: String?,
        val kbps: Int,
    )

    /** A replacement stream for a refusal in the middle of a track. */
    class MidPlayStream(
        val videoId: String,
        val url: String,
        val headers: Map<String, String>,
        val viaProxy: Boolean,
        val client: String,
    )

    /**
     * The videoId behind [url], without changing any state.
     *
     * Used by the playback layer to key its retry budget before it decides
     * whether to ask for a replacement.
     */
    fun videoIdFor(url: String): String? =
        InnerTubeXResolver.extractedFor(url)?.videoId
            ?: recent.entries.firstOrNull { it.value.url == url }?.key

    /**
     * Records that googlevideo refused [url] in the middle of playback and
     * returns what is needed to ask for the same track again.
     *
     * The URL is forgotten and, when InnerTubeX minted it, that client is
     * excluded for this track — exactly as [onPlaybackRefused] always did. The
     * difference is the returned record: the caller can now re-resolve the same
     * videoId instead of being left with only a dead URL.
     */
    fun refuseMidPlay(url: String, responseCode: Int): RefusedStream? {
        // A 429/503 is back-pressure from the host, not a verdict about the
        // client that minted the URL. Forget the URL so the next resolve
        // cannot hand the same rate-limited one back for the rest of its TTL,
        // but do not bench a client or record a refusal for it: the server is
        // asking for time, and another client would be refused just the same.
        if (responseCode in RATE_LIMIT_CODES) {
            recent.entries.firstOrNull { it.value.url == url }?.key?.let(recent::remove)
            return null
        }
        if (responseCode !in REFUSAL_CODES) return null
        // Only googlevideo's URLs say anything about the client that minted
        // them. A module's stream URL answering 404 is that server's business,
        // and must not bench a YouTube client.
        if (url.toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") != true) return null
        val extracted = InnerTubeXResolver.onRefused(url)
        val videoId = extracted?.videoId
            ?: recent.entries.firstOrNull { it.value.url == url }?.key
            ?: return null
        recent.remove(videoId)
        val parsed = url.toHttpUrlOrNull()
        val refused = RefusedStream(
            videoId = videoId,
            url = url,
            mimeType = extracted?.mimeType ?: parsed?.queryParameter("mime"),
            kbps = extracted?.kbps
                ?: parsed?.queryParameter("bitrate")?.toIntOrNull()?.div(1000)
                ?: 0,
        )
        if (lastRefused.size >= MAX_REMEMBERED) lastRefused.clear()
        lastRefused[videoId] = refused
        return refused
    }

    /**
     * Asks for [refused]'s track again: another direct client first, or the
     * proxy when [forceProxy] is set.
     *
     * The container and bitrate of the refused URL are carried into the ask, so
     * the replacement is the same rendition rather than a different encoding
     * whose byte offsets would not line up with the position playback has
     * already read. A failure here is a normal "no answer", not a verdict: the
     * caller's retry budget decides what happens next.
     */
    suspend fun reResolve(refused: RefusedStream, forceProxy: Boolean): MidPlayStream? {
        val parsed = refused.url?.toHttpUrlOrNull()
        val requireM4a =
            refused.mimeType?.contains("mp4", ignoreCase = true) == true ||
                parsed?.queryParameter("mime")?.contains("mp4", ignoreCase = true) == true
        val ceiling = refused.kbps.takeIf { it > 0 } ?: maxKbps()
        val stream = (if (forceProxy) {
            if (!ProxyPlayback.canResolveViaProxy()) return null
            try {
                resolveViaProxy(refused.videoId, ceiling, requireM4a)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                TrackLog.w(TAG, "mid-play re-resolve via proxy hit a linkage failure for ${refused.videoId}: ${e.message}")
                null
            } catch (e: Exception) {
                TrackLog.w(
                    TAG,
                    "mid-play re-resolve via proxy failed for ${refused.videoId}: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                )
                null
            }
        } else {
            try {
                innerTubeXStreamAllowingUploads(
                    refused.videoId,
                    ceiling,
                    requireM4a,
                    viaProxy = false,
                ) ?: directStream(refused.videoId, ceiling, viaProxy = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                TrackLog.w(TAG, "mid-play re-resolve hit a linkage failure for ${refused.videoId}: ${e.message}")
                null
            } catch (e: Exception) {
                TrackLog.w(
                    TAG,
                    "mid-play re-resolve failed for ${refused.videoId}: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                )
                null
            }
        }) ?: return null
        // A successful re-resolve outranks any verdict recorded while every
        // client was being refused: the track is playable again. The caller
        // records the URL only after it has checked that this is the same
        // rendition, so a rejected replacement is not left in [recent].
        unplayable.remove(refused.videoId)
        return MidPlayStream(
            videoId = refused.videoId,
            url = stream.url,
            headers = mediaHeadersFor(stream.url),
            viaProxy = ProxyPlayback.isProxiedUrl(stream.url),
            client = InnerTubeXResolver.extractedFor(stream.url)?.profileId
                ?: PlayerClient.forStreamUrl(stream.url).clientName,
        )
    }

    /**
     * The failure path's version of [reResolve]: the item's URI carries only
     * the videoId, so the refused URL's hints are read from the last refusal
     * recorded for it, if there was one.
     */
    suspend fun reResolveTrack(
        videoId: String,
        forceProxy: Boolean,
        refusedUrl: String? = null,
    ): MidPlayStream? {
        val refused = lastRefused[videoId] ?: RefusedStream(videoId, refusedUrl, null, 0)
        return reResolve(refused, forceProxy)
    }

    /**
     * Puts [url] back in [recent] after a retry of the same URL succeeded.
     *
     * [refuseMidPlay] removes it so no later resolve hands it back blindly;
     * a retry that worked is the evidence that it is good again.
     */
    fun rememberServed(videoId: String, url: String) {
        remember(videoId, url)
    }

    /**
     * Test seam: the route stamp currently stored for [videoId].
     *
     * Lets the cache tests state what a later open would actually follow,
     * without a proxy or a network walk.
     */
    internal fun cachedProxyStampForTest(videoId: String): ProxyPlaybackPolicy.ResolvedUrlStamp? =
        recent[videoId]?.let {
            ProxyPlaybackPolicy.ResolvedUrlStamp(it.proxyGeneration, it.mintedOnAllScope)
        }

    /**
     * As [resolve], but for a file being kept rather than a stream being heard:
     * the best format the *download* setting allows, and no connection has a
     * say. Opus-in-WebM is preferred for app-private downloads; exported files
     * require AAC-in-MP4 because Android's Music collection rejects WebM.
     *
     * Every adaptive audio format YouTube offers is either AAC in MP4 or Opus
     * (or Vorbis) in WebM. Android's media store will not mint an audio row for
     * `audio/webm` — measured on-device as
     * `IllegalArgumentException: Unsupported MIME type audio/webm` out of
     * `ContentResolver.insert`, thrown before a single byte has been fetched.
     * App-private storage has no such restriction, so it keeps Opus; an export
     * asks for MP4 from the start instead of discovering the restriction when
     * it creates the destination.
     *
     * There is no "best available" fallback below. A walk that takes another
     * container can no longer fulfil the destination already chosen: WebM
     * cannot be exported through the Music collection, and silently replacing
     * a requested private Opus download with AAC changes its codec. Running out
     * of the required ladder is therefore a failure with a sentence attached.
     *
     * The container is asked of InnerTubeX up front rather than filtered out
     * of whatever it picks, because it answers with one format per ask: MP4
     * when the destination needs it, its best Opus otherwise. It has no
     * bitrate ceiling of its own, so a rung over [maxKbps] sends the download
     * on to extraction for a lower one, and is kept should extraction fail.
     *
     * Nothing here touches [recent]. That cache exists to keep ExoPlayer's
     * re-opens off the network, and its entries are picked under the *playback*
     * ceiling — seeding it from here would hand a capped connection a stream it
     * was capped to avoid, and reading from it would hand a download whatever
     * bitrate playback happened to settle for. Both directions are wrong, and
     * they are wrong independently of what [maxKbps] says.
     *
     * Extraction sits behind InnerTubeX for the same reason it does in
     * [resolve]: a download reaching a wall playback climbs over has to climb
     * it too, or it fails while the track it is refusing to save is audibly
     * playing.
     *
     * @param maxKbps the ceiling from
     *   [DownloadQuality][com.music.bitchord.data.settings.DownloadQuality].
     *   Passed in rather than read here so that one download resolves at one
     *   bitrate: a setting changed mid-fetch, or a re-resolve after a refusal
     *   (see [Downloader.fetch][com.music.bitchord.download.Downloader.fetch]),
     *   must not splice two different renditions into one file.
     */
    suspend fun resolveForDownload(
        videoId: String,
        maxKbps: Int,
        requireM4a: Boolean = false,
    ): Stream {
        // A download is a playback request too: it must not wait on a warm-up
        // that was deliberately deferred until someone asked for a track.
        InnerTubeXResolver.ensureWarm()
        val stream = downloadStream(videoId, maxKbps, requireM4a)
        val allowed = if (requireM4a) setOf("m4a") else setOf("m4a", "webm")
        check(stream.downloadExtension in allowed) {
            "Can't save ${stream.mimeType} — try again"
        }
        return stream
    }

    private suspend fun downloadStream(videoId: String, maxKbps: Int, requireM4a: Boolean): Stream =
        withContext(TrackLog.about(videoId)) {
            init

            // Whether any client offered AAC at all, as distinct from whether one
            // could be turned into a working URL. Those are different failures and
            // only one of them is worth telling someone to try again about: a track
            // no client has an MP4 for will not have one in five minutes either,
            // while an MP4 that won't probe is a bad afternoon on Google's side.
            var offered = false
            var proxyTried = false

            // The same rule as playback: a download of a track whose player
            // request is known to need the proxy starts there, and a direct
            // walk that fails in the Korean-IP pattern gets one proxy attempt.
            val canProxy = ProxyPlayback.canResolveViaProxy()
            var proxyFallback: Stream? = null
            if (canProxy && ProxyPlayback.isRemembered(videoId)) {
                proxyTried = true
                TrackLog.d(TAG, "proxy-playback: $videoId remembered")
                val rememberedStream = proxyDownloadStream(videoId, maxKbps, requireM4a)
                proxyFallback = rememberedStream
                if (rememberedStream != null && rememberedStream.kbps <= maxKbps) {
                    return@withContext rememberedStream
                }
            }

            // InnerTubeX hands back its best rung of the container asked for, and
            // has no ceiling of its own. Within the setting's ceiling it is the
            // answer; above it (best Opus over Standard's 128kbps) extraction
            // below can pick the lower rung, and this is kept as the fallback.
            var itxFailureReason: String? = null
            // The proxy decision needs the first region/Premium/bot answer even
            // when a later client only times out; [keepTrackSpecific] would
            // replace it, losing both the proxy walk and the bot stop.
            var itxProxyReason: String? = null
            val found = innerTubeXStreamAllowingUploads(
                videoId,
                maxKbps,
                requireM4a,
                onFailure = { reason ->
                    itxFailureReason = UnplayableReason.keepTrackSpecific(itxFailureReason, reason)
                    itxProxyReason = UnplayableReason.keepProxyWorthy(itxProxyReason, reason)
                },
            )
                ?.takeIf { if (requireM4a) it.downloadExtension == "m4a" else it.downloadExtension == "webm" }
            if (found != null) {
                offered = true
                if (found.kbps <= maxKbps) return@withContext found
                TrackLog.d(TAG, "InnerTubeX's ${found.kbps}kbps is over the ${maxKbps}kbps download ceiling for $videoId")
            }

            // The failsafe changes how the URL is found, not which container the
            // destination can accept.
            val format = if (requireM4a) "MP4" else "Opus"
            if (found == null) TrackLog.w(TAG, "InnerTubeX found no usable $format for $videoId; extracting")
            val extracted = runCatching {
                newPipeStream(videoId) { candidates ->
                    // Capped the same way as the player-response selection, off
                    // the same setting, or the failsafe would quietly hand back
                    // a rendition the user said they didn't want to keep.
                    val matching = candidates.filter {
                        if (requireM4a) it.second.isM4a else it.second.isWebmOpus
                    }
                    underCeiling(matching, maxKbps)
                        ?.also { offered = true }
                }
            }
            extracted.getOrNull()?.let { return@withContext it }
            val extractionFailure = extracted.exceptionOrNull()
            if (extractionFailure is CancellationException) throw extractionFailure
            if (extractionFailure != null) {
                TrackLog.w(TAG, "extraction found no $format for $videoId: ${extractionFailure.message}")
            }
            // A download is the one place a country/Premium gate used to leave
            // a proxy-configured listener with no file: the direct walk threw
            // YoutubeMusicPremiumContentException (or InnerTubeX exhausted its
            // catalog), and only a bot exception was treated as proxy-worthy.
            // Both signals now earn the same single proxy walk playback gets.
            val proxyWorthy = extractionFailure?.isProxyWorthyRefusal() == true ||
                itxProxyReason?.let(UnplayableReason::isRegionGate) == true
            if (!proxyTried && canProxy && proxyWorthy) {
                proxyTried = true
                TrackLog.i(TAG, "proxy-playback: $videoId refused direct -> via proxy (download)")
                proxyDownloadStream(videoId, maxKbps, requireM4a)
                    ?.let { return@withContext it }
            }
            if (extractionFailure?.isSignInConfirmNotBot() == true ||
                itxProxyReason?.let(UnplayableReason::isBotChallenge) == true
            ) {
                noteBotChallenge(videoId)
                throw BotChallengeException(BOT_CHALLENGE_REASON)
            }

            // A rung above the ceiling beats no download at all; extraction does the same.
            proxyFallback?.let { return@withContext it }
            found?.let { return@withContext it }
            if (offered) error("지금은 다운로드 가능한 사본에 연결할 수 없습니다 — 다시 시도하십시오")
            error("이 트랙에는 다운로드 가능한 오디오가 없습니다")
        }

    /**
     * A download's stream, asked through the proxy once.
     *
     * Only InnerTubeX is walked here: the extraction failsafe reads the watch
     * page through the app's direct client, and a proxy walk of it would need a
     * second extractor. The player request is the step the gate refuses, and
     * InnerTubeX is what the successful measurement used.
     */
    private suspend fun proxyDownloadStream(
        videoId: String,
        maxKbps: Int,
        requireM4a: Boolean,
        allowBackupRetry: Boolean = true,
    ): Stream? {
        val start = MonotonicClock.nowMs()
        val requestGeneration = ProxyPlayback.currentRouteGeneration
        val found = try {
            innerTubeXStreamAllowingUploads(videoId, maxKbps, requireM4a, viaProxy = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "proxy-playback: $videoId failed: ${e.javaClass.simpleName}: ${e.message}")
            if (ProxyFailure.isProxyDown(e)) {
                if (!allowBackupRetry) {
                    ProxyHealthMonitor.recordBackupUnreachable()
                } else {
                    ProxyHealthMonitor.recordUnreachable("download $videoId")
                    if (YouTubeProxy.usingBackup && !ProxyHealthMonitor.isMarkedUnreachable()) {
                        TrackLog.i(TAG, "proxy-playback: $videoId retrying the download through the backup proxy")
                        return proxyDownloadStream(videoId, maxKbps, requireM4a, allowBackupRetry = false)
                    }
                }
            }
            return null
        } ?: run {
            TrackLog.w(TAG, "proxy-playback: $videoId failed: no stream")
            return null
        }
        val wantedExtension = if (requireM4a) "m4a" else "webm"
        if (found.downloadExtension != wantedExtension) {
            TrackLog.w(TAG, "proxy-playback: $videoId failed: ${found.mimeType} is not $wantedExtension")
            return null
        }
        ProxyHealthMonitor.recordSuccess(allowRouteChange = false)
        val first = ProxyPlayback.markResolved(videoId, found.url, requestGeneration)
        ResolutionFacts.note(videoId, "proxy")
        TrackLog.i(TAG, "proxy-playback: $videoId ok in ${MonotonicClock.nowMs() - start}ms")
        if (first) TrackLog.d(TAG, "proxy-playback: $videoId remembered")
        return found
    }

    // ---- Format selection ---------------------------------------------------

    /**
     * Highest stream at or under the ceiling set for the connection in use; if
     * everything is above it (e.g. Low on a track that only has 130kbps+), take
     * the cheapest available rather than failing.
     */
    private fun <T> pickForQuality(candidates: List<Pair<Int, T>>): T? =
        underCeiling(candidates, maxKbps())

    /**
     * Highest of [candidates] at or under [maxKbps]; if everything is above it
     * — Standard on a track whose AAC ladder starts at 256, say — the cheapest
     * available, because a rung over budget still beats no audio at all.
     */
    private fun <T> underCeiling(candidates: List<Pair<Int, T>>, maxKbps: Int): T? {
        if (candidates.isEmpty()) return null
        val withinBudget = candidates.filter { it.first <= maxKbps }
        return (withinBudget.maxByOrNull { it.first } ?: candidates.minByOrNull { it.first })
            ?.second
    }

    // ---- Player JavaScript ----------------------------------------------------

    /**
     * Guards every call into [YoutubeJavaScriptPlayerManager].
     *
     * Its player-JS cache, including a *failed* parse's exception, lives in
     * static fields with no synchronization, shared by the whole process, and
     * this app resolves more than one track at once by design. A failure is
     * passed straight out rather than answered with `clearAllCaches` and a
     * retry: measured, that retry could only fail again and threw away the
     * parsed player the NewPipe fallback depends on, taking it from 2.7s to
     * 49.8s.
     */
    private val jsPlayerMutex = Mutex()

    private suspend fun <T> jsPlayerManager(block: () -> T): T = jsPlayerMutex.withLock { block() }

    /**
     * YouTube's current player revision, as the number a client quotes to prove
     * it is running that player.
     *
     * Named and shared rather than fetched at each of the three call sites,
     * because there is now a fourth kind of caller that is nothing to do with
     * streaming: [PlaybackTracker] needs one to be issued a tracking block at
     * all, and it has no business knowing that the value comes from parsing
     * YouTube's player JavaScript.
     *
     * Memoised for the process. It is a property of YouTube's deployment, not of
     * a track — the videoId is passed only because NewPipe's API takes one, to
     * decide which player script to fetch — and it changes on the order of days,
     * against a first parse that costs seconds.
     */
    suspend fun signatureTimestamp(videoId: String): Int? {
        cachedSignatureTimestamp?.let { return it }
        return runCatching {
            jsPlayerManager { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }
        }
            .onFailure { TrackLog.w(TAG, "no signature timestamp: ${it.message}") }
            .getOrNull()
            ?.also { cachedSignatureTimestamp = it }
    }

    @Volatile
    private var cachedSignatureTimestamp: Int? = null

    // ---- Validation ---------------------------------------------------------

    private enum class Probe {
        /** Served media bytes; safe to play and to cache. */
        OK,

        /** Answered, but refused this request — the client is the problem. */
        REFUSED,

        /** Never got an answer worth interpreting; blame nothing in particular. */
        UNREACHABLE,
    }

    /**
     * Read the end of a URL before trusting it.
     *
     * This is the whole difference between a track that fails and a track that
     * fails *visibly and instantly*. A URL that 403s is indistinguishable from
     * a good one until something reads from it; hand it to ExoPlayer and the
     * failure surfaces as a track that spins and never starts.
     *
     * The range has to be as large as the real fetch will ask for, not a token
     * one. A URL minted for a session Google has reservations about serves
     * small ranges to anybody — enough to pass a small probe — and then refuses
     * the multi-megabyte ranges actual listening is made of with a 403.
     * The range starts past [AUTH_BOUNDARY_BYTES] when the file is that long:
     * some clients' URLs serve the first megabyte and 403 everything after
     * it, so a probe of the opening passes and playback dies ~50s in.
     * Sixteen kilobytes of the answer still have to actually arrive, so a
     * response that stalls after its headers is a failure too.
     *
     * The headers are the ones the media fetch will really use — see
     * [PlayerClient.forStreamUrl] — so this tests the request that matters
     * rather than a more favourable version of it.
     */
    private fun probe(url: String, viaProxy: Boolean = false): Probe {
        val length = url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
        val start = if (length != null && length > AUTH_BOUNDARY_BYTES + PROBE_READ_BYTES) AUTH_BOUNDARY_BYTES else 0L
        // A host with no range cap of its own reports Long.MAX_VALUE, which
        // must not be added to [start]: that overflowed into a negative end
        // and a 416 read as a refusal. Its real fetch asks for the rest of
        // the file, so the probe does too.
        val window = PlayerClient.rangeBytesFor(url)
        val range = when {
            window != Long.MAX_VALUE -> "bytes=$start-${minOf(start + window, length ?: Long.MAX_VALUE) - 1}"
            length != null -> "bytes=$start-${length - 1}"
            else -> "bytes=$start-"
        }
        val builder = okhttp3.Request.Builder().url(url)
            .header("Range", range)
        val sentHeaders = mediaHeadersFor(url)
        sentHeaders.forEach { (name, value) -> builder.header(name, value) }
        return try {
            (if (viaProxy) proxyProber else prober).newCall(builder.build()).execute().use { response ->
                val contentType = response.header("Content-Type")
                val verdict = when {
                    response.code in REFUSAL_CODES -> Probe.REFUSED
                    response.code !in 200..299 && response.code != 416 -> Probe.UNREACHABLE
                    // A refusal dressed as a success: an error page, or the
                    // consent/captcha interstitial, rather than audio.
                    contentType?.startsWith("audio/") != true -> Probe.REFUSED
                    // Headers can arrive long before a body that never does —
                    // exactly the shaping this whole path exists to sidestep.
                    // Insisting on the bytes is the point: a trickle that
                    // yields its first byte and stalls is a failure too.
                    response.body?.source()?.request(PROBE_READ_BYTES) != true -> Probe.UNREACHABLE
                    else -> Probe.OK
                }
                if (verdict != Probe.OK) {
                    TrackLog.w(
                        TAG,
                        "probe $verdict: HTTP ${response.code} type=$contentType " +
                            "range=$range ${describeMediaUrl(url)} " +
                            "sent=${sentHeaders.keys.sorted()}",
                    )
                }
                verdict
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "probe failed: ${e.message}")
            Probe.UNREACHABLE
        }
    }

    private val REFUSAL_CODES = setOf(403, 404, 410)

    /** Back-pressure, not a refusal: forget the URL but keep the client. */
    private val RATE_LIMIT_CODES = setOf(429, 503)

    /** A stream URL reduced to what describes it and nothing that could be replayed. */
    private fun describeMediaUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return "url=unparseable"
        return "host=${parsed.host} path=${parsed.encodedPath} " +
            "c=${parsed.queryParameter("c")} itag=${parsed.queryParameter("itag")} " +
            "mime=${parsed.queryParameter("mime")} clen=${parsed.queryParameter("clen")} " +
            "pot=${parsed.queryParameter("pot") != null}"
    }

    /**
     * The probe's own client: the app's, but on a short leash.
     *
     * [Http.client]'s 30-second read timeout is right for a stream being
     * consumed as it arrives and far too patient for a yes/no question —
     * waiting it out is indistinguishable from the stall being tested for.
     * Built from the shared client, so the connection pool and DNS are the
     * same ones the real fetch will use.
     */
    private val prober by lazy {
        Http.client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The probe for a URL minted through the proxy. googlevideo authorises it
     * only from the proxy's address, so a direct probe would turn a perfectly
     * good stream into a REFUSED verdict.
     */
    private val proxyProber by lazy {
        Http.proxyMediaClient.newBuilder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private const val PROBE_TIMEOUT_SECONDS = 6L

    /** Where googlevideo stops authorising some clients' URLs (InnerTubeX: "CDN 403 after 1 MiB"). */
    private const val AUTH_BOUNDARY_BYTES = 1024L * 1024

    /** How much of the answer must actually arrive, to catch a stalled body. */
    private const val PROBE_READ_BYTES = 16L * 1024

    /**
     * A URL that [probe] cleared has been refused while actually playing.
     *
     * [probe] runs before playback and not again, so without this [recent]
     * would keep handing back the same dead URL for the rest of its TTL and
     * every replay would fail the same way. So the refusal is fed back: forget
     * the URL, and when InnerTubeX minted it, skip that client for the track.
     *
     * Called from the playback path — see
     * [ChunkedDataSource][com.music.bitchord.playback.ChunkedDataSource].
     */
    fun onPlaybackRefused(url: String, responseCode: Int) {
        refuseMidPlay(url, responseCode)
    }

    /**
     * Forgets a URL whose host asked for a longer pause than a loader thread
     * may take.
     *
     * [onPlaybackRefused] deliberately only handles 403/404/410: those are
     * refusals of the client, and a 429/503 is the host asking for time. But
     * leaving the URL in [recent] is the other half of that decision. The
     * playback path retries the same item after 350ms, the resolving factory
     * asks [resolve] again, and a cached entry hands back the exact URL the
     * host just rate-limited — three times, until the retry budget is gone and
     * the track is skipped. Forgetting it lets the next open mint a fresh URL
     * (another host, another client) instead of repeating a pause nobody
     * waited for.
     *
     * Forgetting [recent] is only half of it. The playback layer pins the URL
     * it chose — [StreamChoice] for every resolved stream and
     * [QualityUpgrade] for a swapped-in rendition — and those pins live above
     * this module and are reopened without asking the resolver again. The app
     * registers [rateLimitedListener] to drop them for the track, so the next
     * open resolves afresh instead of serving the URL that just asked for a
     * pause.
     */
    fun onRateLimited(url: String) {
        // InnerTubeX only knows the googlevideo URLs it minted; a module URL
        // must still reach the listener without loading the extractor.
        val extracted = if (url.toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") == true) {
            InnerTubeXResolver.extractedFor(url)
        } else {
            null
        }
        val videoId = extracted?.videoId
            ?: recent.entries.firstOrNull { it.value.url == url }?.key
        if (videoId != null) {
            recent.remove(videoId)
            lastRefused.remove(videoId)
        }
        // The URL is passed even without a videoId: a forced module upgrade has
        // no YouTube id, and the playback layer can still match the URL to the
        // pin it is serving.
        rateLimitedListener?.invoke(videoId, url)
        if (videoId == null) {
            // Not silent: a URL with no track behind it can still have been
            // pinned by the playback layer, and an unexplained no-op here is
            // the difference between "nothing to forget" and "the cache key
            // did not match".
            TrackLog.w(TAG, "rate-limited URL has no known track: ${LogUrl.of(url)}")
            return
        }
        TrackLog.w(
            TAG,
            "rate-limited URL forgotten for $videoId; the next open will re-resolve",
            about = videoId,
        )
    }

    /**
     * Told the videoId (may be null) and URL of a rate-limited stream so the
     * playback layer can drop its pinned copy. Installed once by the app.
     */
    @Volatile
    var rateLimitedListener: ((videoId: String?, url: String) -> Unit)? = null

    // ---- Failsafe -----------------------------------------------------------

    /**
     * NewPipe's full extractor, kept for the case where every player client has
     * been turned away — it re-derives everything itself and is updated
     * upstream when YouTube changes, so it works when nothing else does.
     *
     * Last rather than first because of what it costs: it scrapes the watch
     * page, which is the request Google shapes hardest, and a shaped response
     * can hold this call open for the better part of a minute. Worth waiting
     * out when the alternative is silence; not worth paying for every track.
     *
     * Driven through [StreamExtractor][org.schabi.newpipe.extractor.stream.StreamExtractor]
     * directly rather than through `StreamInfo.getInfo`, which is the obvious
     * call and the expensive one. `getInfo` assembles everything a video page
     * has — it drives forty-odd extractor methods, among them `getVideoStreams`
     * and `getVideoOnlyStreams`, and YouTube answers those with twenty to
     * thirty formats whose `n` parameter each has to be transformed by running
     * YouTube's player JavaScript. This app then discards every one of them and
     * keeps the audio. Fetching the page and asking only for [audioStreams]
     * pays that transform four or five times instead: measured on-device at
     * 49.8s against 2.3s for the same track over the same connection.
     */
    private suspend fun newPipeStream(
        videoId: String,
        select: (List<Pair<Int, AudioStream>>) -> AudioStream?,
    ): Stream {
        var failure: Exception? = null
        repeat(EXTRACTION_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(EXTRACTION_RETRY_MS * attempt)
            try {
                return extractStream(videoId, select)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                // Not a failed extraction — a method or class the extractor was
                // compiled against that this OS version does not have, which is
                // the same answer every time and so is not worth the remaining
                // attempts. It reaches here as an Error rather than an Exception,
                // and an Error let past this point does not fail the track: it
                // unwinds through ExoPlayer's loader thread, where nothing is
                // catching it, and takes the process with it. That is what a
                // NoSuchMethodError out of NewPipe's URL codec did on every
                // Android below 13 — see the note in the vendored
                // `org.schabi.newpipe.extractor.utils.Utils`. Reported as an
                // Exception so the layers above treat it as the load failure it
                // is, with the type name kept because the message alone ("No
                // static method ...") reads like nothing.
                throw IOException("Extractor cannot run on this device: $e", e)
            } catch (e: Exception) {
                // A bot challenge is not worth three watch-page attempts: it
                // reads the same on every extraction and belongs to the
                // network. Handed straight to the caller, which stops instead
                // of walking the queue.
                if (e.isSignInConfirmNotBot()) {
                    TrackLog.w(TAG, "extraction hit YouTube's bot check for $videoId")
                    throw e
                }
                // A verdict about the content, not about this minute. There was
                // no classification here at all, so the most expensive retry in
                // the app was spent on the one class of failure that cannot
                // benefit from it: an age-restricted track threw
                // AgeRestrictedContentException, which extends
                // ContentNotAvailableException extends ParsingException extends
                // Exception, landed in this clause, and was granted all three
                // attempts with 1s and 2s backoffs — each one re-fetching the
                // watch page and holding [extractionGate] against every other
                // resolve in the process while it did. Three identical refusals
                // per walk, and the walk itself repeated. Named and rethrown on
                // the first one instead.
                permanentReason(e)?.let { reason ->
                    TrackLog.w(TAG, "extraction will not succeed for $videoId: $reason")
                    throw e
                }
                // Worth another go rather than worth giving up on: the common
                // failure here is a shaped or cut-off watch page, which is a
                // fact about this minute rather than about the track, and this
                // is the last thing standing between the listener and silence.
                TrackLog.w(
                    TAG,
                    "extraction attempt ${attempt + 1} of $EXTRACTION_ATTEMPTS failed for $videoId: ${e.message}",
                )
                failure = e
            }
        }
        throw failure ?: IllegalStateException("트랙 사용 불가: 오디오 스트림 없음")
    }

    /**
     * Why [e] means "never", or null if it only means "not just now".
     *
     * The distinction is the difference between one failed track and a request
     * storm, and it is not available from the exception hierarchy: NewPipe files
     * a takedown, a region block and a truncated watch page under the same
     * [ParsingException] ancestry, so a `catch (e: Exception)` treats "this
     * video does not exist" exactly like "the page arrived cut in half". Every
     * retry in this app sat downstream of that conflation.
     *
     * Kept as a message rather than a boolean because the message is what the
     * listener eventually sees, and "This video is age-restricted" is a
     * different thing to be told than `ERROR_CODE_IO_UNSPECIFIED`.
     */
    private fun permanentReason(e: Throwable): String? = when (e) {
        is PermanentlyUnplayableException -> e.message ?: "이 트랙은 재생할 수 없습니다"
        // The whole reason this function exists — see [newPipeStream].
        is AgeRestrictedContentException ->
            if (Innertube.cookie == null) {
                "연령 제한 트랙입니다. 재생하려면 YouTube에 로그인하십시오."
            } else {
                "YouTube가 이 앱에 연령 제한 트랙을 제공하지 않습니다."
            }
        is GeographicRestrictionException -> "이 국가에서는 이용할 수 없는 트랙입니다"
        is UnsupportedContentInCountryException -> "이 국가에서는 이용할 수 없는 트랙입니다"
        // The direct path's own signal for this same gate. It is classified
        // only after [resolveUncached] has had its one chance to re-ask through
        // the proxy, so a configured proxy still gets to play the track.
        is SignInConfirmNotBotException -> "YouTube가 이 네트워크에서 봇 확인을 요구합니다"
        is PaidContentException -> "유료 콘텐츠 트랙입니다"
        is YoutubeMusicPremiumContentException -> {
            // This is the class NewPipe throws when the web player is served
            // the Korea/no-Premium gate. Remembering it lets the rest of the
            // session use the youtube.com fallback instead of retrying a
            // refusal that will read the same on every track.
            MusicGateFallback.observePremiumFailure("YoutubeMusicPremiumContentException")
            "YouTube Music Premium이 필요한 트랙입니다"
        }
        is PrivateContentException -> "비공개 트랙입니다"
        is AccountTerminatedException -> "이 트랙의 채널이 해지되었습니다"
        is SoundCloudGoPlusContentException -> "SoundCloud Go+가 필요한 트랙입니다"
        // ExoPlayer and the coroutine machinery both wrap freely, and the
        // classification has to survive being wrapped or it never fires: the
        // failure that reaches [resolveUncached] arrives as whatever the last
        // layer chose to throw it as. NewPipe files a claim and a truncated
        // page under the same ancestry, and InnerTubeX's StreamResolveException
        // only carries the answer in its message, so the text is classified
        // here before the cause walk continues.
        else -> UnplayableReason.fromMessage(e.message)
            ?: e.cause?.takeIf { it !== e }?.let(::permanentReason)
    }

    /** How many times the watch page is worth asking for before giving up. */
    private const val EXTRACTION_ATTEMPTS = 3

    /** Multiplied by the attempt number, so the wait grows if the first retry doesn't take. */
    private const val EXTRACTION_RETRY_MS = 1000L

    /**
     * One extraction at a time, across the whole app.
     *
     * Extraction is not the network-bound step it looks like. Fetching the
     * watch page is the small part; the expensive part is running YouTube's
     * player JavaScript through Rhino to transform each format's `n`
     * parameter, which is CPU work on a phone against static state NewPipe
     * shares process-wide. Run concurrently it does not share out, it
     * collapses — measured on-device at 1.8s alone, 16.2s with two in flight
     * (both finishing within 35ms of each other, which is the tell), and 30.3s
     * with three. Read-ahead is what puts three or four in flight: the track
     * being waited on plus [AudioCache][com.music.bitchord.playback.AudioCache]'s
     * queue warm-up, every one of them an extraction now that no player client
     * is being served.
     *
     * So they are queued rather than raced. Serialised, three cost about two
     * seconds each in turn instead of thirty seconds each at once, and the one
     * a listener is actually waiting on is behind at most one other — see
     * `QUEUE_LOOKAHEAD`, kept at one for exactly this reason. The bound on how
     * long a single holder can keep the gate is [EXTRACTOR_TIMEOUT_SECONDS].
     */
    private val extractionGate = Mutex()

    private suspend fun extractStream(
        videoId: String,
        select: (List<Pair<Int, AudioStream>>) -> AudioStream?,
    ): Stream = extractionGate.withLock {
        withContext(Dispatchers.IO) {
            val waited = MonotonicClock.nowMs()
            val extractor = ServiceList.YouTube.getStreamExtractor(
                "https://www.youtube.com/watch?v=$videoId",
            )
            extractor.fetchPage()
            val candidates = extractor.audioStreams
                // Progressive only — DASH/HLS entries carry a manifest, not a URL.
                .filter {
                    !it.content.isNullOrBlank() &&
                        it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
                }
            val stream = select(candidates.map { it.averageBitrate to it })
                ?: error("트랙 사용 불가: 오디오 스트림 없음")
            TrackLog.d(
                TAG,
                "resolved $videoId via NewPipe ${stream.format?.name} @ ${stream.averageBitrate}kbps " +
                    "(extraction held the gate ${MonotonicClock.nowMs() - waited}ms)",
            )
            // stream.content is already playable, not raw: YoutubeStreamExtractor
            // resolves the signature cipher and the `n` parameter itself while
            // building audioStreams, so it is handed on as-is.
            Stream(
                url = stream.content,
                kbps = stream.averageBitrate,
                mimeType = stream.mime,
            )
        }
    }

    /**
     * The container, which is all NewPipe's mime type reports.
     *
     * `MediaFormat.WEBMA_OPUS` — what YouTube's Opus arrives as — carries the
     * mime type `audio/webm`, identical to the Vorbis-in-WebM entry beside it.
     * Accurate about the bytes, and useless for telling the codec apart, which
     * is what [isM4a] is asked of the format for instead.
     */
    private val AudioStream.mime: String get() = format?.mimeType.orEmpty()

    /**
     * Whether these bytes are AAC in MP4, asked of the format rather than its
     * name.
     *
     * The name would in fact do here — `MediaFormat.M4A` reports `audio/mp4`,
     * the same thing the player endpoint calls it — but the enum is what
     * actually carries the answer, and the sibling case is a standing warning
     * against reading these mime types as codecs: the format that means Opus
     * says `audio/webm`, so a download demanding Opus by name concluded the
     * track hadn't any and fell through, while playback took the very same
     * stream and played it as Opus.
     */
    private val AudioStream.isM4a: Boolean
        get() = format == MediaFormat.M4A

    private val AudioStream.isWebmOpus: Boolean
        get() = format == MediaFormat.WEBMA_OPUS

    // ---- Video for the moving cover -------------------------------------------

    /** A video-only rendition of an upload, for a muted loop in the cover's place. */
    class VideoLoop(val url: String, val durationMs: Long, val expiresAtMs: Long)

    private val videoLoops = ConcurrentHashMap<String, VideoLoop>()

    /**
     * A video-only stream of [videoId] no taller than [maxHeight] — H.264 first,
     * which every decoder takes and seeks in cheaply, then VP9, then AV1 — or
     * null when there is none to be had. Asked of NewPipe, which hands back
     * URLs already deciphered; playback's own resolver asks only for audio.
     * Kept until shortly before the URL expires.
     */
    suspend fun videoLoop(videoId: String, maxHeight: Int = 480): VideoLoop? {
        val now = System.currentTimeMillis()
        videoLoops[videoId]?.let { if (it.expiresAtMs - now > LOOP_MARGIN_MS) return it }
        // The gate is shared with playback's own extraction, which a listener
        // may be waiting on: a cover's loop only takes it when it is free,
        // never queues in front of a track trying to start.
        var held = false
        for (attempt in 0 until LOOP_GATE_TRIES) {
            if (extractionGate.tryLock()) {
                held = true
                break
            }
            delay(LOOP_GATE_WAIT_MS)
        }
        if (!held) return null
        val found = try {
            run {
                withContext(Dispatchers.IO) {
                    val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                    extractor.fetchPage()
                    val usable = extractor.videoOnlyStreams.filter {
                        !it.content.isNullOrBlank() &&
                            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                            it.height in 1..maxHeight
                    }
                    val pick = usable.sortedWith(
                        compareBy<org.schabi.newpipe.extractor.stream.VideoStream>(
                            { LOOP_ITAGS.indexOf(it.itag).let { at -> if (at < 0) Int.MAX_VALUE else at } },
                            { -it.height },
                        ),
                    ).firstOrNull() ?: return@withContext null
                    val expires = pick.content.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()
                        ?.times(1000) ?: (now + DEFAULT_LOOP_LIFETIME_MS)
                    TrackLog.d(TAG, "video loop for $videoId: itag ${pick.itag} ${pick.height}p")
                    VideoLoop(pick.content, extractor.length * 1000L, expires)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "no video loop for $videoId: ${e.javaClass.simpleName}: ${e.message}")
            null
        } catch (e: LinkageError) {
            TrackLog.w(TAG, "no video loop for $videoId: ${e.javaClass.name}")
            null
        } finally {
            extractionGate.unlock()
        } ?: return null
        if (videoLoops.size >= 64) videoLoops.clear()
        videoLoops[videoId] = found
        return found
    }

    /** 480p and 360p: H.264, then VP9, then AV1. */
    private val LOOP_ITAGS = listOf(135, 134, 244, 243, 397, 396)
    private const val LOOP_MARGIN_MS = 20L * 60 * 1000
    private const val LOOP_GATE_TRIES = 20
    private const val LOOP_GATE_WAIT_MS = 1_000L
    private const val DEFAULT_LOOP_LIFETIME_MS = 5L * 60 * 60 * 1000

    // ---- Cache --------------------------------------------------------------

    /**
     * How many bytes the whole track is, or null if the URL doesn't say.
     *
     * Every progressive googlevideo URL carries the figure as `clen`. It is
     * worth reading from there because the alternative is an HTTP request that
     * reaches the end of the resource: a bounded range never reveals the total,
     * so read-ahead would have no way to know when it was finished. Resolving
     * is memoised, so asking costs nothing beyond the first time.
     */
    suspend fun contentLength(videoId: String): Long? =
        resolve(videoId).toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()

    /**
     * A cached URL, its age, and the proxy route generation that minted it.
     * [proxyGeneration] is null for a direct URL, which survives route changes.
     */
    private class Resolved(
        val url: String,
        val at: Long,
        val proxyGeneration: Long?,
        /** Whether the media fetch for this URL was minted on the ALL_YOUTUBE route. */
        val mintedOnAllScope: Boolean,
    )

    /**
     * Stream URLs already resolved, by videoId — and, since [resolve] only ever
     * stores one that has served bytes, already known good rather than merely
     * recent.
     *
     * Google issues them with several hours of validity, so the ceiling here is
     * chosen for a different reason: a URL is tied to the playback session that
     * minted it, and holding one indefinitely means a stale entry survives long
     * enough to fail a play. Twenty minutes covers a track and the seeking
     * around it while staying well inside the window where the URL is good.
     */
    private val recent = ConcurrentHashMap<String, Resolved>()

    /**
     * The last URL refused for each track, so the playback failure path can
     * ask for the same rendition again after the URL itself has been forgotten.
     * Bounded and session-scoped like [recent].
     */
    private val lastRefused = ConcurrentHashMap<String, RefusedStream>()

    private const val URL_TTL_MS = 20 * 60 * 1000L

    /** Enough for the queue in hand; this is a latency cache, not a store. */
    private const val MAX_REMEMBERED = 32

    private fun remember(
        videoId: String,
        url: String,
        capturedGeneration: Long? = null,
        capturedOnAllScope: Boolean? = null,
    ) {
        if (recent.size >= MAX_REMEMBERED) {
            val cutoff = MonotonicClock.nowMs() - URL_TTL_MS
            recent.entries.removeAll { it.value.at < cutoff }
            if (recent.size >= MAX_REMEMBERED) recent.clear()
        }
        // A Music-only proxy URL is registered by [ProxyPlayback.markResolved]
        // and that stamp is authoritative. An ALL_YOUTUBE URL is not registered
        // there, so [ProxyPlayback.generationFor]'s ALL branch supplies the
        // live route; guarding this on [ProxyPlayback.isProxiedUrl] was the bug:
        // every retried ALL URL (rememberServed passes no capture) lost its
        // stamp and was later fetched directly, which googlevideo answers with
        // 403 because the URL is bound to the proxy's address.
        val registered = ProxyPlayback.generationFor(url)
        val stamp = ProxyPlaybackPolicy.stampFor(
            capturedGeneration = capturedGeneration,
            capturedOnAllScope = capturedOnAllScope,
            registeredGeneration = registered,
            currentAllScope = com.music.bitchord.data.net.YouTubeProxy.config.scope ==
                com.music.bitchord.data.net.ProxyScope.ALL_YOUTUBE,
        )
        recent[videoId] = Resolved(
            url = url,
            at = MonotonicClock.nowMs(),
            proxyGeneration = stamp.proxyGeneration,
            mintedOnAllScope = stamp.mintedOnAllScope,
        )
        // A URL that resolved or answered a retry is no longer described by the
        // refusal that preceded it.
        lastRefused.remove(videoId)
    }
}
