package com.music.bitchord.data.innertube

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * oom1119 (11-19 02:08:49 OOM): the direct and proxy walks each had their own
 * YouTubeCipherService, so the two legs of one resolve ran two EJS solves at
 * once — each copies the 3.8 MB preprocessed player ~10 times on the Java heap
 * and parses it in QuickJS. One shared service means one lock, one solve at a
 * time, and one set of player caches.
 */
class KoProxyCipherShareTest {

    @Test
    fun `the proxy walk uses the direct walk's cipher service`() {
        assertTrue(InnerTubeXResolver.proxySharesCipherForTest())
    }
}
