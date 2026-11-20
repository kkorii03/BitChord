package com.music.bitchord.ko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * w1120-battery: 11-19 02:05 Cruel Summer logged `hq-cache: … fully cached 35.3 MB` for
 * its `#hifi` copy and at 02:21 the automatic download fetched the same byte count again
 * (`fetched … directly`); five more songs did the same, about 185 MB twice. A whole entry
 * from the same source row, codec and length is copied instead.
 */
class DownloadCacheRenditionChoiceTest {

    private val identity = "addon-hires|1440818839|flac"
    private val length = 37_011_202L

    private fun entry(
        key: String = "aC9HkZW2hZk#hifi",
        served: String? = identity,
        length: Long = this.length,
        complete: Boolean = true,
        refused: Boolean = false,
    ) = KoSourceCopy.Entry(key, served, length, complete, refused)

    @Test
    fun theUpgradesWholeCopyOfTheSameFileIsTaken() {
        val entries = listOf(
            entry(key = "aC9HkZW2hZk", served = "youtube|aC9HkZW2hZk|opus", length = 3_900_000L),
            entry(),
        )
        assertEquals("aC9HkZW2hZk#hifi", KoSourceCopy.pick(entries, identity, length))
    }

    @Test
    fun anotherLengthIsAnotherFile() {
        assertNull(KoSourceCopy.pick(listOf(entry(length = length - 1)), identity, length))
    }

    @Test
    fun anotherSourceRowOrCodecIsNotTaken() {
        assertNull(KoSourceCopy.pick(listOf(entry(served = "addon-hires|1440818839|eac3")), identity, length))
        assertNull(KoSourceCopy.pick(listOf(entry(served = "jiosaavn|8bX2|mp4a")), identity, length))
        assertNull(KoSourceCopy.pick(listOf(entry(served = null)), identity, length))
    }

    @Test
    fun aPartOrARefusedRowsCopyIsNotTaken() {
        assertNull(KoSourceCopy.pick(listOf(entry(complete = false)), identity, length))
        assertNull(KoSourceCopy.pick(listOf(entry(refused = true)), identity, length))
        assertNull(KoSourceCopy.pick(listOf(entry(key = "aC9HkZW2hZk#watch")), identity, length))
    }

    @Test
    fun withoutAStatedLengthOrASourceNothingIsTaken() {
        assertNull(KoSourceCopy.pick(listOf(entry()), identity, null))
        assertNull(KoSourceCopy.pick(listOf(entry()), identity, 0L))
        assertNull(KoSourceCopy.pick(listOf(entry()), null, length))
    }
}
