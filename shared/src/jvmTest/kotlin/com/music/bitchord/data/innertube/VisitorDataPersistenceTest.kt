package com.music.bitchord.data.innertube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VisitorDataPersistenceTest {

    private class FakeStore : Innertube.VisitorStore {
        val values = HashMap<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    @Test
    fun `a stored visitor is reused without minting a new one`() {
        val cookie = "visitor-cache-test-${System.nanoTime()}"
        val previous = Innertube.cookie
        val store = FakeStore()
        try {
            Innertube.cookie = cookie
            val key = visitorStorageKey(Innertube.sessionKey())
            val stored = "Cg" + "A".repeat(60)
            store.values[key] = stored
            store.values["$key:bound"] = "0"
            Innertube.initVisitorStore(store)

            assertEquals(stored, runBlocking { Innertube.ensureVisitorData() })
        } finally {
            Innertube.cookie = previous
            Innertube.initVisitorStore(null)
        }
    }
}
