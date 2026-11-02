package com.music.bitchord.playback.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every substitute key [com.music.bitchord.playback.AudioCache] can write is a
 * substitute clock, not YouTube's: `#alt`, the Atmos-split `#alt-noatmos`, and
 * the quality-upgrade `#hifi`/`#hifi-N`. A hand revert's `#original` is still
 * YouTube, and the watch duplicate is whatever it follows.
 */
class SubstituteRenditionKeyTest {

    private val videoId = "video"

    private fun substitute(key: String) =
        AutomixAnalysisSource.isSubstituteRendition(videoId, key)

    @Test
    fun `every alt and hifi suffix is a substitute`() {
        assertTrue(substitute("$videoId#alt"))
        assertTrue(substitute("$videoId#alt-noatmos"))
        assertTrue(substitute("$videoId#hifi"))
        assertTrue(substitute("$videoId#hifi-2"))
        assertTrue(substitute("$videoId#alt#watch"))
    }

    @Test
    fun `the canonical, original and watch keys are youtube`() {
        assertFalse(substitute(videoId))
        assertFalse(substitute("$videoId#original"))
        assertFalse(substitute("$videoId#watch"))
        assertFalse(substitute("$videoId#original#watch"))
    }

    @Test
    fun `a key without its track id still recognises the substitute`() {
        assertTrue(AutomixAnalysisSource.isSubstituteRendition("$videoId#alt-noatmos"))
        assertFalse(AutomixAnalysisSource.isSubstituteRendition(videoId))
    }
}
