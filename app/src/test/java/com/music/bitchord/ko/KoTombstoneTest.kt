package com.music.bitchord.ko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 11-27 16:55:58: CRASH_NATIVE after 37 s with no log, and the next export
 * said only "crash". The tombstone Android keeps names the library and the
 * thread; these lines must come out of it.
 */
class KoTombstoneTest {

    private class Msg {
        val out = ByteArrayOutputStream()
        private fun varint(v: Long) {
            var x = v
            while (x and 0x7fL.inv() != 0L) {
                out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7
            }
            out.write(x.toInt())
        }
        fun int(field: Int, v: Long) = apply { varint((field shl 3).toLong()); varint(v) }
        fun fixed64(field: Int, v: Long) = apply {
            varint(((field shl 3) or 1).toLong())
            for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xff).toInt())
        }
        fun bytes(field: Int, b: ByteArray) = apply {
            varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b)
        }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray())
        fun msg(field: Int, m: Msg) = bytes(field, m.out.toByteArray())
        fun build(): ByteArray = out.toByteArray()
    }

    private fun frame(file: String, function: String = "", offset: Long = 0, pc: Long = 0x1234) =
        Msg().int(1, pc).str(4, function).int(5, offset).str(6, file)

    private fun thread(id: Long, name: String, vararg frames: Msg) =
        Msg().int(1, id).str(2, name).also { t -> frames.forEach { t.msg(4, it) } }

    private fun entry(id: Long, t: Msg) = Msg().int(1, id).msg(2, t)

    private fun sample(): ByteArray = Msg()
        .str(1, "arm64")                       // arch, skipped
        .int(6, 4321)                          // crashing tid
        .msg(10, Msg().int(1, 6).str(2, "SIGABRT").int(3, -6).str(4, "SI_TKILL").int(8, 1).fixed64(9, 0x7fdeadL))
        .str(14, "FORTIFY: pthread_mutex_lock called on a destroyed mutex\n(extra)")
        .msg(15, Msg().str(1, "null pointer dereference"))
        .msg(16, entry(1, thread(1, "com.music.bitchord", frame("/apex/com.android.runtime/lib64/bionic/libc.so", "syscall", 28))))
        .msg(
            16,
            entry(
                4321,
                thread(
                    4321, "DefaultDispatch",
                    frame("/apex/com.android.runtime/lib64/bionic/libc.so", "abort", 164),
                    frame("/data/app/~~x==/com.music.bitchord-y==/lib/arm64/libquickjs.so", "JS_FreeRuntime", 88),
                    frame("/data/app/~~x==/com.music.bitchord-y==/lib/arm64/libquickjs.so", pc = 0xabc),
                ),
            ),
        )
        .msg(16, entry(77, thread(77, "TdlibThread", frame("/data/app/~~x==/com.music.bitchord-y==/lib/arm64/libtdjni.so", "td::Poll", 4))))
        .str(17, "memory maps follow")             // unknown field, skipped
        .build()

    @Test
    fun `the signal abort cause and crashing frames come out of a tombstone`() {
        val lines = nativeCrashLines(sample())
        assertEquals("exit-native: SIGABRT SI_TKILL fault=0x7fdead tid=4321", lines[0])
        assertEquals(
            "exit-native: abort FORTIFY: pthread_mutex_lock called on a destroyed mutex / (extra)",
            lines[1],
        )
        assertEquals("exit-native: cause null pointer dereference", lines[2])
        assertEquals("exit-native: crashed thread 'DefaultDispatch' (4321)", lines[3])
        assertEquals("exit-native: #00 libc.so abort+164", lines[4])
        assertEquals("exit-native: #01 libquickjs.so JS_FreeRuntime+88", lines[5])
        assertEquals("exit-native: #02 libquickjs.so pc 0xabc", lines[6])
    }

    @Test
    fun `other threads follow, ones inside app libraries first`() {
        val lines = nativeCrashLines(sample())
        assertEquals("exit-native: thread 'TdlibThread' (77): libtdjni.so td::Poll+4", lines[7])
        assertEquals("exit-native: thread 'com.music.bitchord' (1): libc.so syscall+28", lines[8])
        assertEquals(9, lines.size)
        assertEquals(5, nativeCrashLines(sample(), maxLines = 5).size)
    }

    @Test
    fun `a cut-off tombstone keeps what came before the cut`() {
        val whole = sample()
        val lines = nativeCrashLines(whole.copyOf(whole.size - 30))
        assertEquals("exit-native: SIGABRT SI_TKILL fault=0x7fdead tid=4321", lines[0])
        assertTrue(lines.toString(), lines.any { it.contains("libquickjs.so JS_FreeRuntime+88") })
    }

    @Test
    fun `bytes that are not a tombstone still name the libraries in them`() {
        val junk = "\u0007garbage /data/app/lib/arm64/libonnxruntime.so\u0000 and libtdjni.so here".toByteArray()
        val lines = nativeCrashLines(junk)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("exit-native: unparsed tombstone bytes=${junk.size} "))
        assertTrue(lines[0], lines[0].endsWith("libs=[libonnxruntime.so, libtdjni.so]"))
        assertEquals(listOf("exit-native: unparsed tombstone bytes=0 libs=[]"), nativeCrashLines(ByteArray(0)))
    }
}
