package com.music.bitchord.data.net

/**
 * The one-line account of a finished track that the diagnostic export reads
 * first: what it was, where it came from, how it resolved, how long it took to
 * make a sound, how long it played, and how its lyrics and their timing landed.
 *
 * Deliberately pure text assembly: everything in the line was already written
 * by other log lines or is a fact about the queue row, so this makes no request
 * and holds no state. The playback service feeds it when the track changes.
 */
object TrackSummaryFormat {

    /**
     * @param source the queue origin, as `kind:id` (for example
     *   `browse:MPREb_...`), or `none` for a row that names no origin.
     * @param resolve `direct`, `proxy` or `cache` — the route the bytes took.
     * @param firstAudioMs milliseconds from the row becoming current to the
     *   first sound, or null when sound never arrived.
     * @param lyrics the provider that supplied the shown lines, `file` for a
     *   local file's own, or `none`.
     * @param sync `measured`, `ai`, `manual` or `none`.
     * @param shiftMs how far the first line moved, or null when nothing moved.
     * @param matchPercent the agreement the timing achieved, when the provider
     *   recorded one.
     * @param manualOffsetMs the listener's own correction for this song.
     * @param ended `natural`, `skipped` or `error`.
     */
    fun format(
        id: String,
        title: String,
        video: Boolean,
        source: String,
        resolve: String,
        firstAudioMs: Long?,
        playedSeconds: Long,
        lyrics: String,
        sync: String,
        shiftMs: Long?,
        matchPercent: Int?,
        manualOffsetMs: Long,
        ended: String,
    ): String = buildString {
        append("track-summary: ").append(id)
        append(" '").append(oneLine(title)).append("'")
        append(" video=").append(if (video) "y" else "n")
        append(" source=").append(oneLine(source).ifBlank { "none" })
        append(" resolve=").append(resolve)
        append(" firstAudio=").append(firstAudioMs ?: "-")
        append(" played=").append(playedSeconds.coerceAtLeast(0L))
        append(" lyrics=").append(oneLine(lyrics).ifBlank { "none" })
        append(" sync=").append(sync)
        append(" shift=").append(shiftMs ?: "-")
        append(" match=").append(matchPercent ?: "-")
        append(" manualOffset=").append(manualOffsetMs)
        append(" ended=").append(ended)
    }

    /**
     * The line a listener asks for from the track menu, so a moment they can
     * see can be found in the log afterwards.
     */
    fun mark(
        id: String,
        title: String,
        positionMs: Long,
        lyricIndex: Int,
        lyricTimeMs: Long,
        video: Boolean,
        source: String,
        sync: String,
    ): String = buildString {
        append("MARK by user: ").append(id)
        append(" '").append(oneLine(title)).append("'")
        append(" pos=").append(positionMs.coerceAtLeast(0L))
        append(" lyricLine=").append(if (lyricIndex < 0) "-" else lyricIndex)
        append('/').append(if (lyricTimeMs < 0L) "-" else lyricTimeMs)
        append(" video=").append(if (video) "y" else "n")
        append(" source=").append(oneLine(source).ifBlank { "none" })
        append(" sync=").append(sync)
    }

    /** A log line is one line: titles and origins must not smuggle newlines in. */
    private fun oneLine(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_TEXT_CHARS)

    private const val MAX_TEXT_CHARS = 100
}

/**
 * The route each videoId's stream took on its last successful resolve.
 *
 * Kept beside [ProxyPlayback] rather than inferred from its memory: a track
 * remembered as proxied may play its next resolve directly if the proxy was
 * switched off in between, and the summary should say what happened, not what
 * was expected.
 */
object ResolutionFacts {

    private const val MAX_ENTRIES = 300

    private val routes = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun note(videoId: String, route: String) {
        if (videoId.isBlank()) return
        if (routes.size > MAX_ENTRIES) routes.clear()
        routes[videoId] = route
    }

    fun routeFor(videoId: String): String? = routes[videoId]
}
