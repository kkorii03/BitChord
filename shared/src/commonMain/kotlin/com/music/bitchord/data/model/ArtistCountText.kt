package com.music.bitchord.data.model

/**
 * The number a localised audience line states, ready to go back into the app's
 * own label.
 *
 * YouTube writes the count in the response's own language: "1.2M subscribers"
 * in English, "구독자 73명" or "월간 시청자 4120만명" in Korean. The old header
 * took the first space-separated word as the number, which on a Korean line is
 * the label itself — "구독자 구독자명" — so the label and the figure are split
 * here instead.
 *
 * Only the figure and its magnitude are kept ("73", "1.2M", "4120만"): the
 * caller supplies the label and, in Korean, the trailing 명. Null when the
 * line carries no number at all, which is the caller's signal to hide the chip
 * rather than draw an empty one.
 */
fun artistCountValue(text: String?): String? {
    val source = text?.trim().orEmpty()
    if (source.isEmpty()) return null
    val match = ARTIST_COUNT.find(source) ?: return null
    val number = match.groupValues[1]
    val magnitude = match.groupValues.getOrNull(2).orEmpty()
    return (number + magnitude).ifBlank { null }
}

private val ARTIST_COUNT = Regex(
    """(\d[\d.,]*)\s*(억|만|천|billion|million|thousand|[BMK])?""",
    RegexOption.IGNORE_CASE,
)
