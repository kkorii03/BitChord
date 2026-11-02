package com.music.bitchord.ko

import org.junit.Assert.assertEquals
import org.junit.Test

class KoDiagnosticsTest {

    @Test
    fun `the target is the callback that ran, not the handler wrapper`() {
        assertEquals(
            "com.music.bitchord.MainActivity.recompose",
            messageTarget(
                ">>>>> Dispatching to Handler (android.os.Handler) {abc123} " +
                    "com.music.bitchord.MainActivity.recompose: 0",
            ),
        )
    }

    @Test
    fun `a line without a handler still names what was dispatched`() {
        assertEquals("something", messageTarget(">>>>> Dispatching to something: 1"))
        assertEquals("unknown", messageTarget(null))
    }

    @Test
    fun `a stall line starts with the prefix featurehealth reads`() {
        assertEquals("main-stall: 245ms Choreographer#doFrame", stallLine(245, "Choreographer#doFrame"))
    }
}
