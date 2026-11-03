package com.music.bitchord.playback

import android.net.FakeUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * An HTTP refusal is a verdict about the URL, not a slow byte: the same URL
 * cannot answer differently on Media3's next try. The app's own recovery
 * re-resolves or falls back instead, so asking the refusing addon again only
 * spends a request.
 */
class PermanentAwareLoadErrorPolicyTest {

    private val policy = PlaybackService.PermanentAwareLoadErrorPolicy()

    private fun http(code: Int): HttpDataSource.InvalidResponseCodeException {
        val spec = DataSpec.Builder()
            .setUri(FakeUri("https://deezer.nom.deno.net/audio"))
            .build()
        return HttpDataSource.InvalidResponseCodeException(
            code,
            "refused",
            IOException("HTTP $code"),
            emptyMap(),
            spec,
            ByteArray(0),
        )
    }

    private fun retryDelay(error: IOException): Long =
        policy.getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(
                    0L,
                    DataSpec.Builder().setUri(FakeUri("https://deezer.nom.deno.net/audio")).build(),
                    0L,
                ),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                error,
                0,
            ),
        )

    @Test
    fun `definitive refusals are not retried`() {
        for (code in listOf(403, 404, 410)) {
            assertEquals(
                "HTTP $code must not be retried",
                C.TIME_UNSET,
                retryDelay(http(code)),
            )
        }
    }

    @Test
    fun `a refusal wrapped by the loader is still recognised`() {
        assertEquals(
            C.TIME_UNSET,
            retryDelay(IOException("load failed", http(403))),
        )
    }

    @Test
    fun `transient statuses keep their retry`() {
        for (code in listOf(500, 501)) {
            assertNotEquals(
                "HTTP $code is still worth another try",
                C.TIME_UNSET,
                retryDelay(http(code)),
            )
        }
    }

    @Test
    fun `a plain transport failure keeps its retry`() {
        assertNotEquals(
            C.TIME_UNSET,
            retryDelay(IOException("connection reset")),
        )
    }
}
