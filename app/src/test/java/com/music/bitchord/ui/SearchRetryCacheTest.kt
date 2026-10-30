package com.music.bitchord.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search cache may answer an anonymous repeat, but never a retry. Before
 * the retry carried `forceNetwork`, an anonymous search that had cached an
 * empty page replayed that page instead of contacting YouTube, so the "no
 * results" retry button did nothing at all.
 */
class SearchRetryCacheTest {

    @Test
    fun anonymousRepeatMayReuseTheCachedPage() {
        assertTrue(
            reuseCachedSearchPage(anonymous = true, forceNetwork = false, hasCachedPage = true),
        )
    }

    @Test
    fun retryNeverReusesTheCachedPageEvenWhenAnonymous() {
        assertFalse(
            reuseCachedSearchPage(anonymous = true, forceNetwork = true, hasCachedPage = true),
        )
    }

    @Test
    fun submittedSearchReachesTheServerEvenWhenCached() {
        assertFalse(
            reuseCachedSearchPage(anonymous = false, forceNetwork = false, hasCachedPage = true),
        )
    }

    @Test
    fun missingCachedPageAlwaysGoesToTheNetwork() {
        assertFalse(
            reuseCachedSearchPage(anonymous = true, forceNetwork = false, hasCachedPage = false),
        )
    }
}
