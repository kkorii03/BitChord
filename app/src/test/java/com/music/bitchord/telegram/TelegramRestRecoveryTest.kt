package com.music.bitchord.telegram

import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Telegram rest regression from 11-01: a slow search was counted as a
 * failed source, so adding chats kept it resting instead of searching them.
 *
 * The fix is kept testable here without TDLib: a search lease is counted, a
 * timeout is not a failure for Telegram (while it still is for other sources),
 * and changing the source's settings clears its rest.
 */
class TelegramRestRecoveryTest {

    @Before
    fun reset() {
        SourceResolver.SourceCooldown.resetForTest()
    }

    @Test
    fun `concurrent searches keep the client open until both finish`() {
        val gate = TelegramClientGate()
        gate.onForeground(false)

        assertTrue(gate.beginSearchLease())
        assertFalse(gate.beginSearchLease())
        assertFalse("the first search finishing must not close the client", gate.endSearchLease())
        assertTrue(gate.shouldBeOpen)
        assertTrue(gate.endSearchLease())
        assertFalse(gate.shouldBeOpen)
    }

    @Test
    fun `a telegram timeout does not count as a source failure`() {
        repeat(3) {
            SourceResolver.SourceCooldown.timedOut(
                configId = "telegram",
                kind = SourceKind.TELEGRAM,
                online = true,
            )
        }

        assertEquals(0L, SourceResolver.SourceCooldown.failureMark("telegram"))
        assertFalse(SourceResolver.SourceCooldown.isResting("telegram"))
    }

    @Test
    fun `another source keeps the old timeout failure rule`() {
        repeat(2) {
            SourceResolver.SourceCooldown.timedOut(
                configId = "addon",
                kind = SourceKind.ADDON,
                online = true,
            )
        }

        assertEquals(2L, SourceResolver.SourceCooldown.failureMark("addon"))
        assertTrue(SourceResolver.SourceCooldown.isResting("addon"))
    }

    @Test
    fun `changing telegram settings clears its cooldown`() {
        SourceResolver.SourceCooldown.failed("telegram")
        SourceResolver.SourceCooldown.failed("telegram")
        assertTrue(SourceResolver.SourceCooldown.isResting("telegram"))

        SourceResolver.clearCooldown("telegram")

        assertFalse(SourceResolver.SourceCooldown.isResting("telegram"))
    }
}
