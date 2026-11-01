package com.music.bitchord.download

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A failed download must not leave the connection permission behind.
 *
 * [Downloads.enqueue] records that a row may spend metered data, or that the
 * song cache can serve it for free. Those answers belong to the attempt that
 * asked for them. If a failure leaves them in place, a later retry of the same
 * video id — even an ordinary tap made after the connection dropped — is
 * treated as already allowed and downloads over cellular.
 */
class DownloadsFailClearsNetworkPermissionTest {

    private val failedId = "video-1"
    private val untouchedId = "video-2"

    @Suppress("UNCHECKED_CAST")
    private val meteredAllowed = field("meteredAllowedIds").get(Downloads) as MutableSet<String>

    @Suppress("UNCHECKED_CAST")
    private val cacheServable = field("cacheServableIds").get(Downloads) as MutableSet<String>

    @Before
    fun setUp() {
        meteredAllowed.clear()
        cacheServable.clear()
    }

    @After
    fun tearDown() {
        meteredAllowed.clear()
        cacheServable.clear()
        DownloadSession.forget(failedId)
        DownloadSession.forget(untouchedId)
    }

    @Test
    fun `a failed download drops its metered and cache permission`() {
        meteredAllowed += failedId
        cacheServable += failedId
        meteredAllowed += untouchedId
        cacheServable += untouchedId

        fail(failedId, "connection lost")

        assertFalse("a retry must ask the Wi-Fi rule again", failedId in meteredAllowed)
        assertFalse("a retry must ask the cache again", failedId in cacheServable)
        assertTrue("another row's permission is not this failure's business", untouchedId in meteredAllowed)
        assertTrue("another row's permission is not this failure's business", untouchedId in cacheServable)
    }

    private fun fail(videoId: String, reason: String) {
        Downloads::class.java.getDeclaredMethod(
            "fail",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
            .invoke(Downloads, videoId, reason, false)
    }

    private fun field(name: String) = Downloads::class.java
        .getDeclaredField(name)
        .apply { isAccessible = true }
}
