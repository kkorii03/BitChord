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
