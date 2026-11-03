package com.music.bitchord.playback

import com.music.bitchord.TestFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loop mix must never loop a music video: its audio rarely matches the
 * album cut its synced lyrics are timed to, so [com.music.bitchord.playback.smart.LoopMix.plan]
 * refuses either side being a video row. The controller owns that wiring, and
 * it was dropped when the v1.9 loop mix was merged in: a video pair took the
 * loop branch with the video flags defaulting to false.
 */
class CrossfadeVideoLoopTest {

    @Test
    fun `the loop plan is told which side is a music video`() {
        val source = TestFiles.file(SOURCE).readText()
        val call = source
            .substringAfter("val plan = LoopMix.plan(")
            .substringBefore("loopPlanKey = key")

        assertTrue(
            "the outgoing video flag is not passed to LoopMix.plan:\n$call",
            call.contains("outVideo = current.isVideoOrigin"),
        )
        assertTrue(
            "the incoming video flag is not passed to LoopMix.plan:\n$call",
            call.contains("inVideo = next.isVideoOrigin"),
        )
    }

    private companion object {
        const val SOURCE =
            "app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt"
    }
}
