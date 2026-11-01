package com.music.bitchord

import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import com.music.bitchord.data.smb.SmbClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The recursive listing walk, driven through an injected lister so a dead
 * wire and a refused folder can be told apart without a server.
 *
 * smbj does not hand `Share.list` a bare [IOException] for a dropped wire: it
 * wraps [TransportException] in [SMBRuntimeException] inside `receive`. These
 * fakes use that real shape so the test proves the branch the app will see.
 */
class SmbLibraryWalkTest {

    private fun file(name: String) = SmbClient.Entry(name, isDirectory = false, size = 1)
    private fun dir(name: String) = SmbClient.Entry(name, isDirectory = true, size = 0)

    @Test
    fun `a wrapped transport failure in a child folder is rethrown for the reconnect`() {
        val skipped = mutableListOf<String>()
        val dropped = SMBRuntimeException(TransportException("wire dropped"))
        val failure = runCatching {
            SmbClient.walkLibrary(
                root = "",
                maxFiles = 100,
                listDirectory = { path ->
                    when (path) {
                        "" -> listOf(dir("A"), dir("B"))
                        "A" -> throw dropped
                        "B" -> listOf(file("b.mp3"))
                        else -> emptyList()
                    }
                },
                logSkip = { path, _ -> skipped += path },
            )
        }.exceptionOrNull()

        // Swallowing this one made the listing return a partial library and
        // left the next folders on a session that was already dead.
        assertTrue("the wrapped transport failure must reach withShare", failure === dropped)
        assertTrue("a transport failure is not a folder to skip", skipped.isEmpty())
        assertTrue(SmbClient.isTransportFailure(dropped))
    }

    @Test
    fun `a bare transport IOException is still rethrown for the reconnect`() {
        val dropped = IOException("wire dropped")
        val failure = runCatching {
            SmbClient.walkLibrary(
                root = "",
                maxFiles = 100,
                listDirectory = { throw dropped },
                logSkip = { _, _ -> },
            )
        }.exceptionOrNull()

        assertTrue(failure === dropped)
        assertTrue(SmbClient.isTransportFailure(dropped))
    }

    @Test
    fun `a server status answer on a child folder is skipped, not retried`() {
        val skipped = mutableListOf<String>()
        val denied = SMBApiException(
            0,
            SMB2MessageCommandCode.SMB2_QUERY_DIRECTORY,
            "access denied",
            null,
        )
        val listing = SmbClient.walkLibrary(
            root = "",
            maxFiles = 100,
            listDirectory = { path ->
                when (path) {
                    "" -> listOf(dir("Bad"), dir("Good"))
                    "Bad" -> throw denied
                    "Good" -> listOf(file("take.flac"))
                    else -> emptyList()
                }
            },
            logSkip = { path, _ -> skipped += path },
        )

        assertEquals(listOf("Good/take.flac"), listing.audio.map { it.path })
        assertEquals(listOf("Bad"), skipped)
        assertFalse(SmbClient.isTransportFailure(denied))
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
