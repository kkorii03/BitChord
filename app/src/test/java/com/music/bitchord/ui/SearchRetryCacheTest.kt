package com.music.bitchord.ui
import com.music.bitchord.ko.ui.reuseCachedSearchPage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search cache may answer a repeat, but never a retry. Before the retry
 * carried `forceNetwork`, a search that had cached an empty page replayed that
 * page instead of contacting YouTube, so the "no results" retry button did
 * nothing at all.
 */
class SearchRetryCacheTest {

    @Test
    fun retryNeverReusesTheCachedPage() {
        assertFalse(
            reuseCachedSearchPage(forceNetwork = true, hasCachedPage = true),
        )
    }

    /**
     * Review F2: a submit used to skip the cached page so the query reached the
     * account's history, but every search is sent without the account now, so
     * the bypass only cost a second request and a flicker. On the old
     * three-argument policy this call does not compile; with `anonymous = false`
     * it returned false.
     */
    @Test
    fun submittedSearchReusesTheCachedPage() {
        assertTrue(
            reuseCachedSearchPage(forceNetwork = false, hasCachedPage = true),
        )
    }

    @Test
    fun missingCachedPageAlwaysGoesToTheNetwork() {
        assertFalse(
            reuseCachedSearchPage(forceNetwork = false, hasCachedPage = false),
        )
    }
}
