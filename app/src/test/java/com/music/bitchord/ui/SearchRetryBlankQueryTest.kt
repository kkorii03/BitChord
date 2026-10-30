package com.music.bitchord.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry button has to work in every source.
 *
 * Library search answers a blank query with the device folder, so its empty
 * state and storage-permission error show a retry with no text in the field.
 * The old blank-query early return made that button inert.
 */
class SearchRetryBlankQueryTest {

    @Test
    fun librarySearchMayRetryItsBlankQuery() {
        assertTrue(retrySearchAllowed(SearchSource.LIBRARY, ""))
        assertTrue(retrySearchAllowed(SearchSource.LIBRARY, "   "))
    }

    @Test
    fun textSourcesStillNeedAQueryToRetry() {
        assertFalse(retrySearchAllowed(SearchSource.YOUTUBE, ""))
        assertFalse(retrySearchAllowed(SearchSource.ADDONS, "   "))
    }

    @Test
    fun aTypedQueryCanBeRetriedInEverySource() {
        SearchSource.entries.forEach { source ->
            assertTrue("$source should retry a typed query", retrySearchAllowed(source, "loved"))
        }
    }
}
