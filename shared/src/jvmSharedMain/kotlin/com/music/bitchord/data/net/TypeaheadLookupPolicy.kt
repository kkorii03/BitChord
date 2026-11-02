package com.music.bitchord.data.net

/**
 * How long typing has to pause before a typeahead lookup is sent.
 *
 * Both typeahead halves — text completions and live media results — share one
 * delay. They used to run at 180ms and 350ms, which meant a normal typing pace
 * (about three keys a second) paused long enough for *both* timers on every
 * keystroke: one letter cost a suggestion request and a media search, and the
 * log showed fifteen requests for a seven-character word. Three hundred
 * milliseconds still collapses a burst without keeping the list off screen.
 */
const val TYPEAHEAD_DEBOUNCE_MS = 300L

/** How many distinct inputs keep their answers for the life of the view model. */
const val TYPEAHEAD_CACHE_ENTRIES = 32

/**
 * The text a typeahead pipeline should ask about, or null when the keystroke is
 * no longer the one on screen — typed past, blanked, or committed as a search.
 *
 * Pure so the selection rule can be tested without a ViewModel or a network.
 */
fun typeaheadLookupTarget(
    input: String,
    currentQuery: String,
    submitted: Boolean,
): String? = input.takeIf { it.isNotBlank() && it == currentQuery && !submitted }

/** The cache key for one input: surrounding space is not part of the question. */
fun typeaheadCacheKey(input: String): String = input.trim()

/**
 * The rows the suggestion dropdown shows: the text as typed, then the
 * completions that are backed by a song, artist or album in the same
 * `music/get_search_suggestions` answer.
 *
 * The endpoint also answers with general YouTube suggestions ("· 로또 당첨
 * 번호", "강진 뉴스"); a row is only kept when every word it adds to the typed
 * text appears in a music entity's title or subtitle. That drops the general
 * rows without guessing from Korean category words. A punctuation-only prefix
 * has no music answer at all, so it keeps just the query action.
 *
 * Duplicates are removed because the rows become LazyColumn keys; two equal
 * completions from the server used to crash the list with "Key … was already
 * used". The typed text always stays first, so submitting exactly what is in
 * the field is the nearest action even when the server names it back.
 */
fun suggestionRows(
    input: String,
    fetched: List<String>,
    musicTerms: List<String>,
): List<String> {
    val typed = input.trim()
    val musicWords = musicTerms.flatMap { it.words() }.toSet()
    // A prefix with no letter or digit is not a query YouTube Music can
    // complete: its endpoint falls back to YouTube's general suggestions,
    // which arrive with the punctuation echoed in front of every row.
    val completions = if (typed.any { it.isLetterOrDigit() }) {
        fetched.mapNotNull { row ->
            row.trim().takeIf { it.isNotBlank() && !it.hasEmoji() }
                ?.takeIf { it.startsWith(typed, ignoreCase = true) }
                ?.takeIf { it.isMusicCompletion(typed, musicWords) }
        }
    } else {
        emptyList()
    }
    return (listOf(typed) + completions.filterNot { it.equals(typed, ignoreCase = true) })
        .distinctBy { it.lowercase() }
}

/**
 * Whether what the completion adds beyond the typed text is accounted for by
 * a music entity. The full extra phrase is checked first (song titles keep
 * their words together), then each word, so a row carrying an unrelated
 * general-YouTube word is dropped.
 */
private fun String.isMusicCompletion(typed: String, musicWords: Set<String>): Boolean {
    val extra = substring(typed.length).trim { !it.isLetterOrDigit() }.lowercase()
    if (extra.isEmpty()) return true
    if (musicWords.any { it.contains(extra) }) return true
    val extraWords = extra.words()
    return extraWords.isNotEmpty() && extraWords.all { word -> musicWords.any { it.contains(word) } }
}

private fun String.words(): List<String> =
    lowercase().split(NON_WORD).filter { it.length >= 2 }

private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

/**
 * Emoji, dingbats and variation selectors only appear in channel-name junk
 * suggestions. The ranges cover the extended-pictographic blocks as well as
 * the older symbol blocks that channels use (⭐ U+2B50, ✅ U+2705, ✨ U+2728).
 */
private val EMOJI_RANGES = listOf(
    0x1F000..0x1FAFF,
    0x2300..0x23FF,
    0x2600..0x27BF,
    0x2B00..0x2BFF,
    0xFE00..0xFE0F,
    0x200D..0x200D,
    0x00A9..0x00A9,
    0x00AE..0x00AE,
    0x203C..0x203C,
    0x2049..0x2049,
    0x2122..0x2122,
    0x2139..0x2139,
    0x2194..0x2199,
    0x21A9..0x21AA,
    0x3030..0x3030,
    0x303D..0x303D,
    0x3297..0x3297,
    0x3299..0x3299,
)

private fun String.hasEmoji(): Boolean {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        if (EMOJI_RANGES.any { codePoint in it }) {
            return true
        }
        index += Character.charCount(codePoint)
    }
    return false
}

/**
 * Whether an answer still belongs on screen. A late response from an older
 * search must never replace the newer one, but the request before it must also
 * be allowed to finish rather than being cancelled mid-socket.
 */
fun isCurrentSearchResponse(requestId: Long, newestRequestId: Long): Boolean =
    requestId == newestRequestId
