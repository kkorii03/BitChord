package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepgramAudioMsTest {

    @Test
    fun theServersDurationSaysHowMuchOfACutStreamItRead() {
        assertEquals(72_140L, Deepgram.parseAudioMs("""{"metadata":{"duration":72.14},"results":{}}"""))
    }

    @Test
    fun noDurationOrABadBodyIsNotAGuess() {
        assertNull(Deepgram.parseAudioMs("""{"results":{}}"""))
        assertNull(Deepgram.parseAudioMs("""{"metadata":{"duration":0}}"""))
        assertNull(Deepgram.parseAudioMs("not json"))
    }
}
