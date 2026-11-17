package com.music.bitchord

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review S2-1: the 1.9 merge dropped upstream's rule that any list longer than
 * the physical window plays through the bounded path, so a shuffle of a whole
 * local library sent thousands of MediaItems to every media controller.
 */
class PlayFromLongListBoundedTest {

    @Test
    fun `playFrom sends a list longer than the window through the bounded path`() {
        val main = File("src/main/java/com/music/bitchord/MainActivity.kt").readText()
        val start = main.indexOf("fun playFrom(")
        assertTrue("playFrom not found", start >= 0)
        val body = main.substring(start, main.indexOf("val play: (List<Song>, Int) -> Unit", start))
        assertTrue(
            "playFrom must route lists above MAX_PHYSICAL_CONTEXT to playBoundedContext",
            Regex("""val bounded = boundedContext \|\| \w+\.size > BoundedQueuePlanner\.MAX_PHYSICAL_CONTEXT""")
                .containsMatchIn(body),
        )
        assertTrue(
            "the bounded branch must use that rule, not boundedContext alone",
            "if (bounded && !CastController.state.value.casting)" in body,
        )
    }
}
