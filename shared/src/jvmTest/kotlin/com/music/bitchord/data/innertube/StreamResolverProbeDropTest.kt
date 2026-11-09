package com.music.bitchord.data.innertube

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A connection dropped before any answer must not decide a stream URL.
 *
 * Emulator rounds 184-187: half the googlevideo probes ended in "unexpected
 * end of stream", each one skipped the only direct client, and the track
 * never sounded although the same URL probed fine seconds later. The probe
 * now asks the same URL once more after a drop, and only after a drop.
 */
class StreamResolverProbeDropTest {

    private enum class Reply { DROP, AUDIO, FORBIDDEN }

    /** Serves [plan] one connection at a time; returns the URL and the accept count. */
    private fun <T> withServer(plan: List<Reply>, block: (url: String, accepted: AtomicInteger) -> T): T {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicInteger()
        val worker = thread(isDaemon = true) {
            try {
                for (reply in plan) {
                    server.accept().use { socket ->
                        accepted.incrementAndGet()
                        readHeaders(socket)
                        val out = socket.getOutputStream()
                        when (reply) {
                            Reply.DROP -> Unit // closed with no answer at all
                            Reply.AUDIO -> {
                                val body = ByteArray(32 * 1024)
                                out.write(
                                    ("HTTP/1.1 206 Partial Content\r\nContent-Type: audio/webm\r\n" +
                                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                                )
                                out.write(body)
                            }
                            Reply.FORBIDDEN -> out.write(
                                "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                            )
                        }
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                // The server socket closed under a pending accept: the test is over.
            }
        }
        return try {
            block("http://127.0.0.1:${server.localPort}/videoplayback?clen=20000", accepted)
        } finally {
            server.close()
            worker.join(2_000)
        }
    }

    private fun readHeaders(socket: Socket) {
        val input = socket.getInputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return
        }
    }

    @Test
    fun `a connection dropped before any answer is asked once more`() {
        withServer(listOf(Reply.DROP, Reply.AUDIO)) { url, accepted ->
            assertEquals("OK", StreamResolver.probeVerdictForTest(url))
            assertEquals(2, accepted.get())
        }
    }

    @Test
    fun `a URL that keeps dropping is given up after one more ask`() {
        withServer(listOf(Reply.DROP, Reply.DROP, Reply.DROP)) { url, accepted ->
            assertEquals("UNREACHABLE", StreamResolver.probeVerdictForTest(url))
            assertEquals(2, accepted.get())
        }
    }

    @Test
    fun `an answer is never asked twice`() {
        withServer(listOf(Reply.FORBIDDEN, Reply.AUDIO)) { url, accepted ->
            assertEquals("REFUSED", StreamResolver.probeVerdictForTest(url))
            assertEquals(1, accepted.get())
        }
    }
}
