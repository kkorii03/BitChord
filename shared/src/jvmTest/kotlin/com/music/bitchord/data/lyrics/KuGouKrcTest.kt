package com.music.bitchord.data.lyrics

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KRC is KuGou's word-timed lyric file: `krc1`, a fixed-key XOR, zlib, then
 * `<offset,duration,0>` tags. The fixture is built here from made-up words,
 * never from a real song.
 */
class KuGouKrcTest {

    private val key = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
    )

    private fun encode(plain: String): String {
        val deflated = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out).use { it.write(plain.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val scrambled = ByteArray(deflated.size) { index ->
            (deflated[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
        return Base64.getEncoder().encodeToString("krc1".toByteArray(Charsets.US_ASCII) + scrambled)
    }

    @Test
    fun decodesLineAndWordTimes() {
        val lines = assertNotNull(
            KuGou.decodeKrc(
                encode(
                    "[ti:test]\n" +
                        "[1000,2000]<0,500,0>zib<500,500,0>wub<1000,500,0>vok\n" +
                        "[4000,1000]<0,1000,0>flarn",
                ),
            ),
        )
        assertEquals(2, lines.size)
        assertEquals(listOf(1000L, 4000L), lines.map { it.timeMs })
        assertEquals("zibwubvok", lines[0].text)
        assertEquals(listOf("zib", "wub", "vok"), lines[0].words.map { it.text })
        assertEquals(listOf(1000L, 1500L, 2000L), lines[0].words.map { it.startMs })
        assertEquals(listOf(1500L, 2000L, 2500L), lines[0].words.map { it.endMs })
        assertTrue(lines.all { it.isWordSynced })
    }

    @Test
    fun corruptKrcIsNoAnswer() {
        assertNull(KuGou.decodeKrc(""))
        assertNull(KuGou.decodeKrc("not-base64!!"))
        assertNull(KuGou.decodeKrc(Base64.getEncoder().encodeToString("nope".toByteArray())))
        val badBody = "krc1".toByteArray(Charsets.US_ASCII) + ByteArray(64) { 0x7f }
        assertNull(KuGou.decodeKrc(Base64.getEncoder().encodeToString(badBody)))
    }

    @Test
    fun emptyKrcHasNoLines() {
        val lines = assertNotNull(KuGou.decodeKrc(encode("[ti:test]")))
        assertTrue(lines.isEmpty())
    }
}