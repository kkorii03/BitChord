package com.music.bitchord.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One damaged entry must cost only itself.
 *
 * The load used to be a single `runCatching` around the whole loop: any bad row
 * left the playlist list empty and the next save persisted that emptiness.
 */
class LocalPlaylistStoreTest {

    @Test
    fun `a damaged entry is skipped and the others are kept`() {
        val raw = """
            [
              {"id":"a","title":"Alpha","songs":[{"videoId":"v1","title":"One","artist":"A"}]},
              {"id":"b","songs":[]},
              {"id":"c","title":"Gamma","songs":"not an array"},
              {"id":"d","title":"Delta","songs":[]}
            ]
        """.trimIndent()

        val parsed = parsePlaylists(raw)!!

        assertEquals(listOf("a", "d"), parsed.playlists.map { it.id })
        assertTrue(parsed.damaged)
        assertEquals("v1", parsed.playlists.first().songs.single().videoId)
    }

    @Test
    fun `a damaged song row is skipped inside a readable entry`() {
        val raw = """
            [
              {"id":"a","title":"Alpha","songs":[
                {"videoId":"v1","title":"One","artist":"A"},
                {"title":"no id"},
                "not an object",
                {"videoId":"v2","title":"Two","artist":"B"}
              ]}
            ]
        """.trimIndent()

        val parsed = parsePlaylists(raw)!!

        assertEquals(listOf("a"), parsed.playlists.map { it.id })
        assertEquals(listOf("v1", "v2"), parsed.playlists.single().songs.map { it.videoId })
        assertTrue(parsed.damaged)
    }

    @Test
    fun `an intact file is not marked damaged`() {
        val raw = """[{"id":"a","title":"Alpha","songs":[{"videoId":"v1","title":"One","artist":"A"}]}]"""

        val parsed = parsePlaylists(raw)!!

        assertEquals(1, parsed.playlists.size)
        assertEquals(false, parsed.damaged)
    }

    @Test
    fun `an empty array is a valid, empty file`() {
        val parsed = parsePlaylists("[]")!!

        assertEquals(emptyList<String>(), parsed.playlists.map { it.id })
        assertEquals(false, parsed.damaged)
    }

    @Test
    fun `a file that is not an array is unreadable rather than partly damaged`() {
        assertNull(parsePlaylists("""{"id":"a"}"""))
        assertNull(parsePlaylists("not json at all"))
    }

    @Test
    fun `the old null spellings read back as absent`() {
        val raw = """[{"id":"a","title":"Alpha","songs":[{"videoId":"v1","title":"One","artist":"A","thumbnailUrl":"null","albumName":null}]}]"""

        val song = parsePlaylists(raw)!!.playlists.single().songs.single()

        assertNull(song.thumbnailUrl)
        assertNull(song.albumName)
    }
}
