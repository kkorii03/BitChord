package com.music.bitchord.ko

import com.music.bitchord.playback.CrossfadeController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class KoCrossfadeAnalysisWakeTest {

    @Test
    fun `analysis completion wakes the parked ticker`() = runBlocking {
        val completions = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)
        val controller = CrossfadeController(
            scope = this,
            active = { error("the wake does not read the player") },
            standby = { error("the wake does not read the player") },
            onHandoff = { _, _ -> },
            analysisCompleted = completions,
        )
        val job = controller.koLaunchAnalysisWake(this)
        completions.emit(Unit)
        assertEquals(Unit, withTimeout(1_000L) { controller.ko.tickerWake.receive() })
        job.cancel()
    }
}
