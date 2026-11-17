package com.music.bitchord.ko

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review FX2 C2b-3: with "뮤직비디오 영상 재생" off, the mini player and the
 * glass bar still streamed and decoded the MV picture, because the engine's
 * predicate never read the switch.
 */
class KoMusicVideoPictureTest {

    @Test
    fun `switch off wants no picture for a music video`() {
        assertFalse(
            KoMusicVideoPicture.pictureWanted(
                isVideo = true,
                pictureless = false,
                showsPicture = true,
                mvVideoOn = false,
            ),
        )
    }

    @Test
    fun `switch on keeps the picture for a music video`() {
        assertTrue(
            KoMusicVideoPicture.pictureWanted(
                isVideo = true,
                pictureless = false,
                showsPicture = true,
                mvVideoOn = true,
            ),
        )
    }

    @Test
    fun `the old conditions still hold with the switch on`() {
        assertFalse(KoMusicVideoPicture.pictureWanted(isVideo = false, pictureless = false, showsPicture = true, mvVideoOn = true))
        assertFalse(KoMusicVideoPicture.pictureWanted(isVideo = true, pictureless = true, showsPicture = true, mvVideoOn = true))
        assertFalse(KoMusicVideoPicture.pictureWanted(isVideo = true, pictureless = false, showsPicture = false, mvVideoOn = true))
    }
}
