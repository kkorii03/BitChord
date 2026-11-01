package com.music.bitchord.data.listentogether

import com.music.bitchord.auth.AuthStore
import com.music.bitchord.testutil.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A party token is a session credential: it must live in the encrypted store,
 * not in the plainly readable `bitchord_listen_together` preferences. A build
 * that still has the old plaintext entry has to move it out the first time it
 * is read.
 */
class PartyTokenStorageTest {

    private val plain = FakePreferences()
    private val encrypted = FakePreferences()
    private val store = PartyTokenStore(plain.prefs, fakeAuthStore(encrypted))

    @Test
    fun `a saved party token never reaches the plain preference file`() {
        store.save("party-secret")

        assertEquals("party-secret", encrypted.values["party_token"])
        assertFalse(plain.values.containsKey("party_token"))
        assertEquals("party-secret", store.load())
    }

    @Test
    fun `a legacy plaintext party token moves into the encrypted store`() {
        plain.values["party_token"] = "legacy-secret"

        assertEquals("legacy-secret", store.load())

        assertFalse(plain.values.containsKey("party_token"))
        assertEquals("legacy-secret", encrypted.values["party_token"])
    }

    @Test
    fun `clearing a party token removes both copies`() {
        plain.values["party_token"] = "legacy-secret"
        store.load()

        store.clear()

        assertFalse(plain.values.containsKey("party_token"))
        assertFalse(encrypted.values.containsKey("party_token"))
        assertNull(store.load())
    }

    /** AuthStore without its encrypted-file constructor; its secrets stay in the map. */
    private fun fakeAuthStore(preferences: FakePreferences): AuthStore {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val store = allocate.invoke(unsafe, AuthStore::class.java) as AuthStore
        AuthStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(store, preferences.prefs)
        return store
    }
}
