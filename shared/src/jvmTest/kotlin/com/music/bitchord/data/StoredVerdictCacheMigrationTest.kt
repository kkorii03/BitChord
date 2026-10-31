package com.music.bitchord.data

import com.music.bitchord.data.innertube.MusicGateFallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A version bump must revalidate old "no answer" entries without throwing away
 * old settled hits, which would spend one catalogue lookup per row again.
 */
class StoredVerdictCacheMigrationTest {

    private class FakeStore : MusicGateFallback.Store {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    @Test
    fun `a v3 hit is kept and a v3 miss is revalidated`() {
        val store = FakeStore()
        store.values["catalog_song_mapping_v3"] = """
            {"version":1,"entries":{
              "hit":{"value":"catalogue-id\u001Ftitle\u001Fartist\u001Falbum\u001Falbum-id","at":1},
              "miss":{"value":"none","at":1}
            }}
        """.trimIndent()

        val cache = StoredVerdictCache(
            storeKey = "catalog_song_mapping_v4",
            limit = 100,
            legacyKey = "catalog_song_mapping_v3",
            legacyDropValue = "none",
        )
        cache.ensureLoaded(store)

        assertEquals(
            "catalogue-id\u001Ftitle\u001Fartist\u001Falbum\u001Falbum-id",
            cache.get("hit")?.value,
        )
        assertNull(cache.get("miss"), "the old miss must be looked up once under the new rules")
        val migrated = store.values["catalog_song_mapping_v4"].orEmpty()
        assertEquals(true, migrated.contains("catalogue-id"))
        assertEquals(false, migrated.contains("\"none\""))
    }

    @Test
    fun `an existing new store is not overwritten by the old one`() {
        val store = FakeStore()
        store.values["catalog_song_mapping_v4"] =
            """{"version":1,"entries":{"new":{"value":"new-hit","at":2}}}"""
        store.values["catalog_song_mapping_v3"] =
            """{"version":1,"entries":{"old":{"value":"old-hit","at":1}}}"""

        val cache = StoredVerdictCache(
            storeKey = "catalog_song_mapping_v4",
            limit = 100,
            legacyKey = "catalog_song_mapping_v3",
            legacyDropValue = "none",
        )
        cache.ensureLoaded(store)

        assertEquals("new-hit", cache.get("new")?.value)
        assertNull(cache.get("old"))
    }
}
