package com.music.bitchord.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SYNC step's free measurement decodes the next video's audio, so it must
 * obey the low-power switch even while the step itself stays planned for the
 * stored timing.
 */
class NextPrepFreeMeasureTest {

    @Test
    fun aVideoIsMeasuredOnlyWhenHeavyWorkIsAllowed() {
        assertTrue(
            NextPrepPolicy.freeMeasureWanted(
                recordTimed = true,
                isVideo = true,
                worthMeasuring = true,
                heavyWorkAllowed = true,
            ),
        )
        assertFalse(
            NextPrepPolicy.freeMeasureWanted(
                recordTimed = true,
                isVideo = true,
                worthMeasuring = true,
                heavyWorkAllowed = false,
            ),
        )
    }

    @Test
    fun aSongOrAnUnmeasuredAnswerSkipsTheFreeDecode() {
        assertFalse(
            NextPrepPolicy.freeMeasureWanted(
                recordTimed = true,
                isVideo = false,
                worthMeasuring = true,
                heavyWorkAllowed = true,
            ),
        )
        assertFalse(
            NextPrepPolicy.freeMeasureWanted(
                recordTimed = true,
                isVideo = true,
                worthMeasuring = false,
                heavyWorkAllowed = true,
            ),
        )
        assertFalse(
            NextPrepPolicy.freeMeasureWanted(
                recordTimed = false,
                isVideo = true,
                worthMeasuring = true,
                heavyWorkAllowed = true,
            ),
        )
    }
}
