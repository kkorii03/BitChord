package com.music.bitchord.data.cover

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
 */
fun videoThumbnailUrl(videoId: String?): String? {
    val id = videoId?.trim().orEmpty()
    if (!id.matches(VIDEO_ID)) return null
    return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}

private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

private val YT_LEGACY_THUMBNAIL = Regex(
    """^https?://i\.ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{11})/""" +
        """(?:hqdefault|sddefault|default|0|1|2|3|hq1|hq2|hq3)\.(?:jpg|webp)""" +
        """(?:\?.*)?$""",
    RegexOption.IGNORE_CASE,
)
