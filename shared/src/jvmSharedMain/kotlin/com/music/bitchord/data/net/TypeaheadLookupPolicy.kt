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
 * The rows the suggestion dropdown shows: the text as typed, then YouTube's
 * completions without a repeat of it.
 *
 * Duplicates are removed because the rows become LazyColumn keys; two equal
 * completions from the server used to crash the list with "Key … was already
 * used". The typed text always stays first, so submitting exactly what is in
 * the field is the nearest action even when the server names it back.
 */
fun suggestionRows(input: String, fetched: List<String>): List<String> =
    (listOf(input) + fetched.filterNot { it.equals(input, ignoreCase = true) })
        .distinctBy { it.lowercase() }

/**
 * Whether an answer still belongs on screen. A late response from an older
 * search must never replace the newer one, but the request before it must also
 * be allowed to finish rather than being cancelled mid-socket.
 */
fun isCurrentSearchResponse(requestId: Long, newestRequestId: Long): Boolean =
    requestId == newestRequestId
