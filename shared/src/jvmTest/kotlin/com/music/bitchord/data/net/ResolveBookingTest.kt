package com.music.bitchord.data.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One resolve's bookkeeping once a proxy leg races the InnerTubeX walk past
 * its window (Sol review of task/o-proxyrace, 2026-11-09):
 *  1. a cancelled walk whose blocking call finishes after the proxy stream was
 *     adopted must not book itself the ResolutionOrder winner or a miss;
 *  2. a proxy answer that is not adopted (the walk won) must leave no proxy
 *     memory, so the next start of the track does not skip a working path.
 */
class ResolveBookingTest {

    @Test
    fun `a walk that passed its own check before the proxy was adopted cannot overwrite it`() {
        val booking = ResolveBooking()
        // The walk's job is still active when it checks, the proxy is adopted,
        // then the walk writes: the interleaving a check-then-write allowed.
        val adopted = CountDownLatch(1)
        val walk = thread {
            booking.served("itx:VISIONOS", "InnerTubeX VISIONOS", stillWalking = {
                thread { booking.adoptProxy("proxy (late)"); adopted.countDown() }
                // Give the adoption every chance to run between check and write.
                adopted.await(300, TimeUnit.MILLISECONDS)
                true
            })
        }
        walk.join()
        assertTrue(adopted.await(2, TimeUnit.SECONDS))
        assertNull(booking.winnerKey(), "a late proxy start is booked to no path")
        assertEquals("proxy (late)", booking.winnerLabel())
    }

    @Test
    fun `a walk that answers after the proxy was adopted books nothing`() {
        val booking = ResolveBooking()
        booking.adoptProxy("proxy (late)")
        assertFalse(booking.served("itx:TVHTML5_SIMPLY", "InnerTubeX TVHTML5_SIMPLY"))
        assertNull(booking.winnerKey())
        val misses = AtomicInteger()
        assertFalse(booking.bookMiss(stillWalking = { true }) { misses.incrementAndGet() })
        assertEquals(0, misses.get(), "a walk the proxy stopped is not booked a miss")
    }

    @Test
    fun `a walk that served before the proxy won is cleared by the adoption`() {
        val booking = ResolveBooking()
        assertTrue(booking.served("itx:VISIONOS", "InnerTubeX VISIONOS"))
        booking.adoptProxy("proxy")
        assertNull(booking.winnerKey())
    }

    @Test
    fun `a stopped walk books nothing even without a proxy`() {
        val booking = ResolveBooking()
        assertFalse(booking.served("itx:VISIONOS", "x", stillWalking = { false }))
        assertNull(booking.winnerKey())
        val misses = AtomicInteger()
        booking.bookMiss(stillWalking = { false }) { misses.incrementAndGet() }
        assertEquals(0, misses.get())
    }

    @Test
    fun `an ordinary start is booked as before`() {
        val booking = ResolveBooking()
        assertTrue(booking.served("direct", "direct IOS"))
        assertEquals("direct", booking.winnerKey())
        assertEquals("direct IOS", booking.winnerLabel())
        val misses = AtomicInteger()
        assertTrue(booking.bookMiss(stillWalking = { true }) { misses.incrementAndGet() })
        assertEquals(1, misses.get())
    }

    @Test
    fun `a proxy answer the walk beat leaves no proxy memory`() {
        val booking = ResolveBooking()
        val remembered = AtomicInteger()
        // The leg finished just before the walk was picked.
        booking.proxyAnswered { remembered.incrementAndGet() }
        assertTrue(booking.served("itx:VISIONOS", "InnerTubeX VISIONOS"))
        assertEquals(0, remembered.get(), "the track must not be remembered as needing the proxy")
    }

    @Test
    fun `an adopted proxy answer is remembered once`() {
        val booking = ResolveBooking()
        val remembered = AtomicInteger()
        booking.proxyAnswered { remembered.incrementAndGet() }
        assertEquals(0, remembered.get(), "nothing is written before adoption")
        booking.adoptProxy("proxy")
        booking.adoptProxy("proxy")
        assertEquals(1, remembered.get())
    }
}
