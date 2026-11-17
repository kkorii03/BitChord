package com.music.bitchord.data.canvas

import com.music.bitchord.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Review FX2 C4a-2: turning 영상 캔버스 off kept painting (and streaming) the MV
 * loops already cached this session, because the switch was not part of the
 * cache key.
 */
class CanvasCacheKeyTest {

    @Test
    fun `the video canvas switch is part of the cache key`() {
        val before = AppSettings.videoCanvas.value
        try {
            AppSettings.videoCanvas.value = true
            val on = CanvasRepository.cacheKey("song|abc", spotifyFirst = false)
            AppSettings.videoCanvas.value = false
            val off = CanvasRepository.cacheKey("song|abc", spotifyFirst = false)
            assertNotEquals(on, off)
            AppSettings.videoCanvas.value = true
            assertEquals(on, CanvasRepository.cacheKey("song|abc", spotifyFirst = false))
        } finally {
            AppSettings.videoCanvas.value = before
        }
    }
}
