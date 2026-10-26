package com.music.bitchord.playback.smart

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class RefCountedKeyedMutexTest {

    @Test
    fun `a lock stays for a caller that is still waiting`() {
        val locks = RefCountedKeyedMutex<String>()

        val first = locks.acquire("pair")
        val waiting = locks.acquire("pair")
        assertSame(first, waiting)

        locks.release("pair", first)

        val third = locks.acquire("pair")
        assertSame(first, third)
        locks.release("pair", waiting)
        locks.release("pair", third)

        val afterEveryoneLeft = locks.acquire("pair")
        assertNotSame(first, afterEveryoneLeft)
    }
}
