package com.music.bitchord.playback

import com.music.bitchord.data.TrackLog
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueShuffleLogTest {

    @Test
    fun `an unchanged shuffle state is not logged again`() {
        val lines = mutableListOf<String>()
        val previous = TrackLog.echo
        TrackLog.echo = { _, _, message, _ ->
            synchronized(lines) { lines += message }
        }
        try {
            QueueShuffle.setEnabled(false)
            synchronized(lines) { lines.clear() }

            QueueShuffle.setEnabled(false)
            QueueShuffle.setEnabled(false)
            assertEquals(0, synchronized(lines) { lines.count { it.startsWith("mode: shuffle=") } })

            QueueShuffle.setEnabled(true)
            QueueShuffle.setEnabled(true)
            assertEquals(1, synchronized(lines) { lines.count { it.startsWith("mode: shuffle=") } })
        } finally {
            TrackLog.echo = previous
            QueueShuffle.setEnabled(false)
        }
    }
}
