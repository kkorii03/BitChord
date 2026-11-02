package com.music.bitchord.startup

import com.music.bitchord.auth.AuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * A reader that arrives before the startup thread has restored the session
 * must see nothing and wait for the publication, never open the store itself.
 */
class StartupSessionTest {

    @Before
    fun reset() = StartupSession.resetForTest()

    @Test
    fun `a reader cannot observe the session before it is published`() = runBlocking {
        assertNull("the session must start unpublished", StartupSession.publishedOrNull())

        val reader = async(Dispatchers.Default) {
            withTimeoutOrNull(200) { StartupSession.awaitPublished() } to
                StartupSession.publishedOrNull()
        }
        // The reader timed out on the wait and saw no store: only a published
        // session can be observed.
        assertNull("a reader observed a session before it was published", reader.await().second)

        val store = fakeStore()
        StartupSession.publish(store)

        assertSame("the published store is readable", store, StartupSession.publishedOrNull())
        assertSame(
            "waiting readers receive the published store",
            store,
            withTimeoutOrNull(5_000) { StartupSession.awaitPublished() },
        )
    }

    private fun fakeStore(): AuthStore {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, AuthStore::class.java) as AuthStore
    }
}
