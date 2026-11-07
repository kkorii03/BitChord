package com.music.bitchord.data.lyrics

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * QQ Music's QRC cipher: three DES passes over a fixed 24-byte key, then zlib.
 *
 * The key is not a secret — it is the constant QQ's own player carries — so
 * this is scrambling, not security. Ported operation for operation from the
 * reference implementation the lane measured, so the output matches what QQ's
 * client decrypts. The decrypted document is read by [KaraokeLrc]; [QqMusic]
 * fetches it. Never throws: the input comes straight off the network.
 */
internal object QrcCipher {

    /** A real sheet is under 100 KB; past this the stream is a bomb, not lyrics. */
    private const val MAX_INFLATED_BYTES = 512_000

    fun decryptHex(hex: String): String? = runCatching {
        val text = hex.trim()
        if (text.isEmpty() || text.length % 2 != 0) return@runCatching null
        val encrypted = ByteArray(text.length / 2)
        for (index in encrypted.indices) {
            val high = text[2 * index].hexDigit() ?: return@runCatching null
            val low = text[2 * index + 1].hexDigit() ?: return@runCatching null
            encrypted[index] = ((high shl 4) or low).toByte()
        }
        if (encrypted.size % 8 != 0) return@runCatching null
        val inflater = Inflater()
        try {
            inflater.setInput(tripleDecrypt(encrypted))
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                // inflate answers 0 only for a finished, starved or dictionary
                // stream; the first is handled above and the rest cannot finish.
                if (read == 0) return@runCatching null
                out.write(buffer, 0, read)
                if (out.size() > MAX_INFLATED_BYTES) return@runCatching null
            }
            out.toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF")
        } finally {
            inflater.end()
        }
    }.getOrNull()

    private fun Char.hexDigit(): Int? = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }

    private fun tripleDecrypt(data: ByteArray): ByteArray {
        // D-E-D: the outer and inner passes use the reversed key schedule.
        val first = keyschedule(QQKEY.copyOfRange(16, 24), decrypt = true)
        val second = keyschedule(QQKEY.copyOfRange(8, 16), decrypt = false)
        val third = keyschedule(QQKEY.copyOfRange(0, 8), decrypt = true)
        val out = ByteArray(data.size - data.size % 8)
        var offset = 0
        while (offset + 8 <= data.size) {
            val block = crypt(crypt(crypt(data.copyOfRange(offset, offset + 8), first), second), third)
            block.copyInto(out, offset)
            offset += 8
        }
        return out
    }

    private fun crypt(input: ByteArray, key: List<ByteArray>): ByteArray {
        val state = IntArray(2)
        ip(state, input)
        for (index in 0 until 15) {
            val swap = state[1]
            state[1] = f(state[1], key[index]) xor state[0]
            state[0] = swap
        }
        state[0] = f(state[1], key[15]) xor state[0]
        val out = ByteArray(8)
        invip(state, out)
        return out
    }

    private fun keyschedule(key: ByteArray, decrypt: Boolean): List<ByteArray> {
        var c = 0
        var d = 0
        for (index in 0 until 28) c = c or bitnum(key, KEY_PERM_C[index], 31 - index)
        for (index in 0 until 28) d = d or bitnum(key, KEY_PERM_D[index], 31 - index)
        val out = Array(16) { ByteArray(6) }
        for (index in 0 until 16) {
            val shift = KEY_RND_SHIFT[index]
            c = ((c shl shift) or (c ushr (28 - shift))) and 0xfffffff0.toInt()
            d = ((d shl shift) or (d ushr (28 - shift))) and 0xfffffff0.toInt()
            val round = if (decrypt) 15 - index else index
            for (bit in 0 until 24) {
                out[round][bit / 8] = (
                    out[round][bit / 8].toInt() or bitnumintr(c, KEY_COMPRESSION[bit], 7 - (bit % 8))
                    ).toByte()
            }
            for (bit in 24 until 48) {
                out[round][bit / 8] = (
                    out[round][bit / 8].toInt() or bitnumintr(d, KEY_COMPRESSION[bit] - 27, 7 - (bit % 8))
                    ).toByte()
            }
        }
        return out.toList()
    }

    /** The initial permutation. */
    private fun ip(state: IntArray, input: ByteArray) {
        state[0] = 0
        state[1] = 0
        for (index in IP_LEFT.indices) state[0] = state[0] or bitnum(input, IP_LEFT[index], 31 - index)
        for (index in IP_RIGHT.indices) state[1] = state[1] or bitnum(input, IP_RIGHT[index], 31 - index)
    }

    /** The final permutation, writing the block out. */
    private fun invip(state: IntArray, out: ByteArray) {
        for (index in 0 until 8) {
            val byte = (index + 4) % 8
            var value = 0
            for (bit in 0 until 4) {
                value = value or bitnumintr(state[1], byte + 8 * bit, 7 - 2 * bit)
                value = value or bitnumintr(state[0], byte + 8 * bit, 6 - 2 * bit)
            }
            out[index] = (value and 0xff).toByte()
        }
    }

    private fun f(state: Int, key: ByteArray): Int {
        val t1 = (bitnumintl(state, 31, 0) or
            ((state and 0xf0000000.toInt()) ushr 1) or bitnumintl(state, 4, 5) or
            bitnumintl(state, 3, 6) or ((state and 0x0f000000) ushr 3) or bitnumintl(state, 8, 11) or
            bitnumintl(state, 7, 12) or ((state and 0x00f00000) ushr 5) or bitnumintl(state, 12, 17) or
            bitnumintl(state, 11, 18) or ((state and 0x000f0000) ushr 7) or bitnumintl(state, 16, 23))
        val t2 = (bitnumintl(state, 15, 0) or ((state and 0x0000f000) shl 15) or bitnumintl(state, 20, 5) or
            bitnumintl(state, 19, 6) or ((state and 0x00000f00) shl 13) or bitnumintl(state, 24, 11) or
            bitnumintl(state, 23, 12) or ((state and 0x000000f0) shl 11) or bitnumintl(state, 28, 17) or
            bitnumintl(state, 27, 18) or ((state and 0x0000000f) shl 9) or bitnumintl(state, 0, 23))
        val bytes = intArrayOf(
            (t1 ushr 24) and 0xff, (t1 ushr 16) and 0xff, (t1 ushr 8) and 0xff,
            (t2 ushr 24) and 0xff, (t2 ushr 16) and 0xff, (t2 ushr 8) and 0xff,
        ).also { values ->
            for (index in 0 until 6) values[index] = values[index] xor (key[index].toInt() and 0xff)
        }
        val mixed = (SBOXS[0][sboxbit(bytes[0] shr 2)] shl 28) or
            (SBOXS[1][sboxbit(((bytes[0] and 3) shl 4) or (bytes[1] shr 4))] shl 24) or
            (SBOXS[2][sboxbit(((bytes[1] and 0xf) shl 2) or (bytes[2] shr 6))] shl 20) or
            (SBOXS[3][sboxbit(bytes[2] and 0x3f)] shl 16) or
            (SBOXS[4][sboxbit(bytes[3] shr 2)] shl 12) or
            (SBOXS[5][sboxbit(((bytes[3] and 3) shl 4) or (bytes[4] shr 4))] shl 8) or
            (SBOXS[6][sboxbit(((bytes[4] and 0xf) shl 2) or (bytes[5] shr 6))] shl 4) or
            SBOXS[7][sboxbit(bytes[5] and 0x3f)]
        var out = 0
        for (position in PERMUTATION.indices) out = out or bitnumintl(mixed, PERMUTATION[position], position)
        return out
    }

    /** One bit of [key]'s [position]th bit, placed at [into]. */
    private fun bitnum(key: ByteArray, position: Int, into: Int): Int =
        ((key[(position / 32) * 4 + 3 - (position % 32) / 8].toInt() shr (7 - (position % 8))) and 1) shl into

    private fun bitnumintr(value: Int, position: Int, into: Int): Int =
        ((value ushr (31 - position)) and 1) shl into

    private fun bitnumintl(value: Int, position: Int, into: Int): Int =
        ((value shl position) and Int.MIN_VALUE) ushr into

    private fun sboxbit(value: Int): Int =
        (value and 0x20) or ((value and 0x1f) shr 1) or ((value and 0x01) shl 4)

    /** QQ's own fixed key; the three passes use its halves. */
    private val QQKEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    private val SBOX1 = intArrayOf(
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
    )

    private val SBOX2 = intArrayOf(
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
    )

    private val SBOX3 = intArrayOf(
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
    )

    private val SBOX4 = intArrayOf(
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
    )

    private val SBOX5 = intArrayOf(
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
    )

    private val SBOX6 = intArrayOf(
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
    )

    private val SBOX7 = intArrayOf(
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
    )

    private val SBOX8 = intArrayOf(
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
    )

    private val SBOXS = arrayOf(SBOX1, SBOX2, SBOX3, SBOX4, SBOX5, SBOX6, SBOX7, SBOX8)

    private val KEY_RND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    private val KEY_PERM_C = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
    )

    private val KEY_PERM_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5,
        60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    )

    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    private val IP_LEFT = intArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
    )

    private val IP_RIGHT = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
        60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6,
    )

    private val PERMUTATION = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24,
    )
}