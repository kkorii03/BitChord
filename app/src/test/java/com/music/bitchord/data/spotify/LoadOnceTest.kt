package com.music.bitchord.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** The local-playlist store runs its disk read once, before any write can see it. */
class LoadOnceTest {

    @Test
    fun `the load runs once however many callers arrive`() {
        val loads = AtomicInteger()
        val gate = LoadOnce { loads.incrementAndGet() }

        gate.ensureLoaded()
        gate.ensureLoaded()
        gate.ensureLoaded()

        assertEquals(1, loads.get())
    }

    @Test
    fun `a reset asks for the next caller to load again`() {
        val loads = AtomicInteger()
        val gate = LoadOnce { loads.incrementAndGet() }

        gate.ensureLoaded()
        gate.reset()
        gate.ensureLoaded()

        assertEquals(2, loads.get())
    }

    @Test
    fun `two threads meeting the gate load once`() {
        val loads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gate = LoadOnce {
            entered.countDown()
            release.await()
            loads.incrementAndGet()
        }

        val first = Thread { gate.ensureLoaded() }.apply { start() }
        entered.await()
        val second = Thread { gate.ensureLoaded() }.apply { start() }
        release.countDown()
        first.join()
        second.join()

        assertEquals(1, loads.get())
    }
}
