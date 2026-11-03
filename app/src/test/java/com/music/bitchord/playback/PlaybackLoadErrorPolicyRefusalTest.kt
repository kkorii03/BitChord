package com.music.bitchord.playback

import android.net.FakeUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A refused stream URL must not be reloaded by Media3.
 *
 * 403/404/410 are the server's answer for this URL, not a transient fault:
 * asking for the same bytes again only repeats the refusal (and, for an HQ
 * addon, sends three more requests before the YouTube fallback runs).
 */
class PlaybackLoadErrorPolicyRefusalTest {

    private val policy = PlaybackService.PermanentAwareLoadErrorPolicy()

    @Test
    fun `a hard HTTP refusal is not retried on the same URL`() {
        for (code in listOf(403, 404, 410)) {
            assertEquals(
                "HTTP $code must be handed to the failure path, not retried",
                C.TIME_UNSET,
                policy.getRetryDelayMsFor(info(code)),
            )
        }
    }

    @Test
    fun `a transient HTTP failure keeps the default retry`() {
        val delay = policy.getRetryDelayMsFor(info(500))
        assertNotEquals(C.TIME_UNSET, delay)
        assertTrue("HTTP 500 is worth another go", delay >= 0L)
    }

    private fun info(code: Int): LoadErrorHandlingPolicy.LoadErrorInfo {
        val spec = DataSpec(FakeUri("https://hq.example/audio"))
        val exception = HttpDataSource.InvalidResponseCodeException(
            code,
            "HTTP $code",
            IOException("HTTP $code"),
            emptyMap(),
            spec,
            ByteArray(0),
        )
        return LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(0L, spec, 0L),
            MediaLoadData(C.DATA_TYPE_MEDIA),
            exception,
            1,
        )
    }
}
