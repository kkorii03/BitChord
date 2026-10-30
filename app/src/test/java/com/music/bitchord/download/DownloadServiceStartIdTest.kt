package com.music.bitchord.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A drain that passes `stopSelf()` must not tear down a start request that
 * arrived while it was finishing. Only `stopSelf(id)` leaves that request
 * alive, so the ledger has to say when the id it holds is still the latest.
 */
class DownloadServiceStartIdTest {

    @Test
    fun `a start that arrives after the drain began prevents the stop`() {
        val ledger = StartIdLedger()
        val drainStartId = ledger.record(5)
        ledger.record(6)

        assertNull(ledger.stopIdAfter(drainStartId))
    }

    @Test
    fun `a drain with no later start stops with the latest id`() {
        val ledger = StartIdLedger()
        val drainStartId = ledger.record(5)

        assertEquals(5, ledger.stopIdAfter(drainStartId))
    }

    @Test
    fun `a start that arrives during the same drain is not ignored`() {
        val ledger = StartIdLedger()
        ledger.record(1)
        val drainStartId = ledger.record(2)
        ledger.record(3)
        ledger.record(4)

        assertNull(ledger.stopIdAfter(drainStartId))
        assertEquals(4, ledger.latest())
    }
}
