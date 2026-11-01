package com.music.bitchord

import com.music.bitchord.data.smb.SmbClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The recursive listing walk, driven through an injected lister so a dead
 * wire and a refused folder can be told apart without a server.
 */
class SmbLibraryWalkTest {

    private fun file(name: String) = SmbClient.Entry(name, isDirectory = false, size = 1)
    private fun dir(name: String) = SmbClient.Entry(name, isDirectory = true, size = 0)

    @Test
    fun `a transport failure in a child folder is rethrown for the reconnect`() {
        val skipped = mutableListOf<String>()
        val failure = runCatching {
            SmbClient.walkLibrary(
                root = "",
                maxFiles = 100,
                listDirectory = { path ->
                    when (path) {
                        "" -> listOf(dir("A"), dir("B"))
                        "A" -> throw IOException("wire dropped")
                        "B" -> listOf(file("b.mp3"))
                        else -> emptyList()
                    }
                },
                logSkip = { path, _ -> skipped += path },
            )
        }.exceptionOrNull()

        // Swallowing this one made the listing return a partial library and
        // left the next folders on a session that was already dead.
        assertTrue("transport IOException must reach withShare", failure is IOException)
        assertTrue("a transport failure is not a folder to skip", skipped.isEmpty())
    }

    @Test
    fun `a refused child folder is skipped and the rest still lists`() {
        val skipped = mutableListOf<String>()
        val listing = SmbClient.walkLibrary(
            root = "",
            maxFiles = 100,
            listDirectory = { path ->
                when (path) {
                    "" -> listOf(dir("Bad"), dir("Good"))
                    "Bad" -> throw IllegalStateException("access denied")
                    "Good" -> listOf(file("take.flac"))
                    else -> emptyList()
                }
            },
            logSkip = { path, _ -> skipped += path },
        )

        assertEquals(listOf("Good/take.flac"), listing.audio.map { it.path })
        assertEquals(listOf("Bad"), skipped)
    }

    @Test
    fun `a refused root is not skipped`() {
        val failure = runCatching {
            SmbClient.walkLibrary(
                root = "Music",
                maxFiles = 100,
                listDirectory = { throw IllegalStateException("access denied") },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `audio cap does not skip cover images beside the tracks`() {
        val listing = SmbClient.walkLibrary(
            root = "",
            maxFiles = 2,
            listDirectory = {
                listOf(file("a.mp3"), file("b.mp3"), file("cover.jpg"), file("c.mp3"))
            },
        )

        assertEquals(listOf("a.mp3", "b.mp3"), listing.audio.map { it.path })
        assertEquals(listOf("cover.jpg"), listing.images.map { it.path })
    }
}
