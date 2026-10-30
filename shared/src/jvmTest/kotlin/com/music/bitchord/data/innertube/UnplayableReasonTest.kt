package com.music.bitchord.data.innertube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UnplayableReasonTest {

    @Test
    fun `a copyright claim is a definitive verdict`() {
        val message = "Got error UNPLAYABLE: It was blocked due to the claimed content by SME"
        assertTrue(UnplayableReason.isDefinitive(message))
        assertTrue(UnplayableReason.isDefinitelyGone(message))
        assertTrue(UnplayableReason.isTrackSpecific(message))
    }

    @Test
    fun `the generic unavailable answer waits for the proxy retry`() {
        val message = "StreamResolveException: This track is unavailable"
        assertTrue(UnplayableReason.isDefinitive(message))
        assertFalse(UnplayableReason.isDefinitelyGone(message))
        assertFalse(UnplayableReason.isTrackSpecific(message))
    }

    @Test
    fun `a bare unavailable answer is definitive`() {
        assertTrue(UnplayableReason.isDefinitive("StreamResolveException: unavailable"))
        assertTrue(UnplayableReason.isDefinitive("Video unavailable"))
    }

    @Test
    fun `a deleted or private upload is definitive`() {
        assertTrue(UnplayableReason.isDefinitive("Private video"))
        assertTrue(UnplayableReason.isDefinitive("This video has been removed by the uploader"))
        assertTrue(UnplayableReason.isDefinitive("비공개 트랙입니다"))
        assertTrue(UnplayableReason.isDefinitive("삭제된 동영상입니다"))
    }

    @Test
    fun `rate limits are retryable`() {
        assertFalse(UnplayableReason.isDefinitive("HTTP 429 Too Many Requests"))
        assertFalse(UnplayableReason.isDefinitive("429"))
        assertFalse(UnplayableReason.isDefinitelyGone("HTTP 429 Too Many Requests"))
    }

    @Test
    fun `timeouts and dropped sockets are retryable`() {
        assertFalse(UnplayableReason.isDefinitive("Read timed out"))
        assertFalse(UnplayableReason.isDefinitive("timeout"))
        assertFalse(UnplayableReason.isDefinitive("Connection reset by peer"))
        assertFalse(UnplayableReason.isDefinitive(IOException("connection reset").message))
    }

    @Test
    fun `a service unavailable is not a takedown`() {
        assertFalse(UnplayableReason.isDefinitive("HTTP 503 Service Unavailable"))
        assertFalse(UnplayableReason.isDefinitive("temporarily unavailable"))
    }

    @Test
    fun `blank or unknown failures are retryable`() {
        assertNull(UnplayableReason.fromMessage(null))
        assertNull(UnplayableReason.fromMessage(""))
        assertNull(UnplayableReason.fromMessage("   "))
        assertFalse(UnplayableReason.isDefinitive("something odd happened"))
    }

    @Test
    fun `a region or premium gate is definitive but not track specific`() {
        assertTrue(UnplayableReason.isDefinitive("not available in your country"))
        assertFalse(UnplayableReason.isTrackSpecific("not available in your country"))
        assertFalse(UnplayableReason.isDefinitelyGone("not available in your country"))
    }

    @Test
    fun `unrelated addon and module errors are not track verdicts`() {
        assertFalse(UnplayableReason.isDefinitive("AddonClient: 사용할 수 없는 주소입니다"))
        assertFalse(UnplayableReason.isDefinitive("모듈을 이용할 수 없습니다"))
        assertFalse(UnplayableReason.isDefinitive("not available"))
        assertFalse(UnplayableReason.isTrackSpecific("AddonClient: 사용할 수 없는 주소입니다"))
    }

    @Test
    fun `UNPLAYABLE is definitive but needs the explicit reason to be track specific`() {
        assertTrue(UnplayableReason.isDefinitive("Got error UNPLAYABLE: This track is unavailable"))
        assertFalse(UnplayableReason.isDefinitelyGone("Got error UNPLAYABLE: This track is unavailable"))
        assertFalse(UnplayableReason.isTrackSpecific("Got error UNPLAYABLE: This track is unavailable"))
    }
}
