package com.music.bitchord.data.cover

import com.music.bitchord.data.model.artworkAt

/**
 * A URL for artwork that fills a square row without letterboxing.
 *
 * YouTube's older stills (`hqdefault`, `sddefault`, `default`, `0`…`3`,
 * `hq1`…`hq3`) are 4:3 frames with black bars baked in, so a centre crop to a
 * square row keeps the bars. `hq720` is the same picture as true 16:9 and has
 * no bars; it is requested without the query string the original carried.
 *
 * Every other URL, including square album art, is returned untouched.
 */
fun squareFriendlyThumbnail(url: String?): String? {
    val value = url ?: return null
    val match = YT_LEGACY_THUMBNAIL.matchEntire(value.trim()) ?: return url
    return "https://i.ytimg.com/vi/${match.groupValues[1]}/hq720.jpg"
}

/**
 * The still YouTube always has for a video id. Used where a row reached the
 * UI without artwork of its own: a missing thumbnail is a black square, while
 * a plain `hqdefault` frame is still recognisable and cheap.
 *
 * This is the guaranteed rung of [videoThumbnailCandidates]: smaller videos
 * have no `maxresdefault` or `hq720` at all, so anything that cannot retry a
 * failed image load (the notification, the lock screen) must use this one.
 */
fun videoThumbnailUrl(videoId: String?): String? =
    videoThumbnailCandidates(videoId).lastOrNull()

/**
 * The stills to try for [videoId], best first.
 *
 * A cover upload often only has the legacy sizes: `maxresdefault` and `hq720`
 * 404 for it while `hqdefault` exists. The UI starts at the best one and the
 * image loader drops to [videoThumbnailUrl] when that request fails.
 */
fun videoThumbnailCandidates(videoId: String?): List<String> {
    val id = videoId?.trim().orEmpty()
    if (!id.matches(VIDEO_ID)) return emptyList()
    return listOf(
        "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
        "https://i.ytimg.com/vi/$id/hqdefault.jpg",
    )
}

/**
 * The still to retry after a YouTube thumbnail failed to load, or null when
 * there is nothing safer to ask for.
 *
 * The player, the mini player and every list row upscale a legacy still to
 * `hq720`, which does not exist for every upload. When that request 404s,
 * `hqdefault` is the same video's frame and is always served.
 */
fun youtubeStillFallback(url: String?): String? {
    val match = YT_STILL.matchEntire(url?.trim().orEmpty()) ?: return null
    val id = match.groupValues[1]
    if (match.groupValues[2].equals("hqdefault", ignoreCase = true)) return null
    return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}

/**
 * [artworkAt] for a surface that cannot retry a failed load.
 *
 * The notification and the lock screen read one URL and draw whatever comes
 * back, so a legacy still is left as `hqdefault` instead of being rewritten to
 * an `hq720` that may not exist.
 */
fun String?.mediaSessionArtworkAt(px: Int): String? {
    val value = this ?: return null
    if (YT_LEGACY_THUMBNAIL.matchEntire(value.trim()) != null) return value
    return artworkAt(px)
}

private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

private val YT_LEGACY_THUMBNAIL = Regex(
    """^https?://i\.ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/""" +
        """(?:hqdefault|sddefault|default|0|1|2|3|hq1|hq2|hq3)\.(?:jpg|webp)""" +
        """(?:\?.*)?$""",
    RegexOption.IGNORE_CASE,
)

private val YT_STILL = Regex(
    """^https?://(?:i|img)\.ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/""" +
        """(maxresdefault|hq720|sddefault|hqdefault|mqdefault|default)\.(?:jpg|webp)""" +
        """(?:\?.*)?$""",
    RegexOption.IGNORE_CASE,
)
