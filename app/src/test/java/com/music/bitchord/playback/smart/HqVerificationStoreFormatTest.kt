package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The text format behind the high-quality verdict cache. It is written as one
 * tab-separated line per pair, so a bad line must be dropped without taking the
 * rest of the cache with it, and an old line must still be readable.
 */
class HqVerificationStoreFormatTest {

    private lateinit var prefs: FakePreferences
    private lateinit var context: Context
    private val cachedField = HqVerificationStore::class.java.getDeclaredField("cached").apply {
        isAccessible = true
    }

    @Before
    fun setUp() {
        prefs = FakePreferences()
        context = FakeContext.create(prefs)
        cachedField.set(HqVerificationStore, null)
    }

    @After
    fun tearDown() {
        cachedField.set(HqVerificationStore, null)
    }

    private fun verified(tier: HqTier = HqTier.LOSSLESS_16) = HqVerificationStore.Verified(
        offsetMs = 1234L,
        rate = 1.001,
        matchPercent = 87,
        tier = tier,
    )

    @Test
    fun `a verified pair is written as one tab separated line and read back`() {
        val value = verified()

        HqVerificationStore.put(context, "yt1", "src1", "track1", value)

        assertEquals(
            "yt1\u0001src1\u0001track1\t1234\t1.001\t87\tLOSSLESS_16",
            prefs.values["verified"],
        )
        assertEquals(value, HqVerificationStore.get(context, "yt1", "src1", "track1"))
        assertEquals(
            listOf(HqVerificationStore.Entry("yt1", "src1", "track1", value)),
            HqVerificationStore.entries(context),
        )
        assertEquals(HqTier.LOSSLESS_16, HqVerificationStore.bestTier(context, "yt1"))
    }

    @Test
    fun `a words checked verdict keeps its mark across a reload and plain lines stay unmarked`() {
        val checked = verified().copy(wordsChecked = true)
        HqVerificationStore.put(context, "yt1", "src1", "track1", checked)
        assertEquals(
            "yt1\u0001src1\u0001track1\t1234\t1.001\t87\tLOSSLESS_16\t\t\twords",
            prefs.values["verified"],
        )

        cachedField.set(HqVerificationStore, null)

        assertEquals(checked, HqVerificationStore.get(context, "yt1", "src1", "track1"))
        prefs.values["verified"] = "yt2\u0001src1\u0001track1\t1234\t1.001\t87\tLOSSLESS_16\tTitle\tArtist"
        cachedField.set(HqVerificationStore, null)
        assertFalse(HqVerificationStore.get(context, "yt2", "src1", "track1")!!.wordsChecked)
    }

    // Phone 11-25: the violin's stored "words" mark came from hearing no words at all.
    @Test
    fun `a nameless row's old marks are owed again and a new one survives a reload`() {
        val line = "src1\u0001track1\t1234\t1.001\t87\tLOSSLESS_16\tTitle\tVarious Artists\t"
        prefs.values["verified"] = "yt1\u0001${line}words\nyt2\u0001${line}seen"
        listOf("yt1", "yt2").forEach {
            val old = HqVerificationStore.get(context, it, "src1", "track1")!!
            assertEquals(1234L, old.offsetMs)
            assertFalse(old.wordsChecked)
            assertFalse(old.guardSeen)
        }

        val heard = verified().copy(sourceTitle = "Title", sourceArtist = "Various Artists", wordsChecked = true)
        HqVerificationStore.put(context, "yt1", "src1", "track1", heard)
        cachedField.set(HqVerificationStore, null)

        assertEquals(heard, HqVerificationStore.get(context, "yt1", "src1", "track1"))
    }

    // Review 11-15 (H3): a cached copy swapped in before the words guard plays
    // with no search. Its verdict has to be told from one the guard has seen,
    // or every good copy would be heard again on every play.
    @Test
    fun `a guard seen verdict keeps its own mark and is never read as words checked`() {
        val seen = verified().copy(guardSeen = true)
        HqVerificationStore.put(context, "yt1", "src1", "track1", seen)
        assertEquals(
            "yt1\u0001src1\u0001track1\t1234\t1.001\t87\tLOSSLESS_16\t\t\tseen",
            prefs.values["verified"],
        )

        cachedField.set(HqVerificationStore, null)

        val back = HqVerificationStore.get(context, "yt1", "src1", "track1")!!
        assertTrue(back.guardSeen)
        assertFalse(back.wordsChecked)

        // A line from before the guard carries neither mark: that copy is still owed a hearing.
        prefs.values["verified"] = listOf(
            "yt2\u0001src1\u0001track1\t1234\t1.001\t87\tLOSSLESS_16\tTitle\tArtist",
            "yt3\u0001src1\u0001track1\t1234\t1.001\t87\tLOSSLESS_16\tTitle\tArtist\twords",
        ).joinToString("\n")
        cachedField.set(HqVerificationStore, null)
        val legacy = HqVerificationStore.get(context, "yt2", "src1", "track1")!!
        assertFalse(legacy.guardSeen)
        assertFalse(legacy.wordsChecked)
        val checked = HqVerificationStore.get(context, "yt3", "src1", "track1")!!
        assertTrue(checked.wordsChecked)
        assertFalse(checked.guardSeen)

        // Both marks at once still write the stronger one.
        HqVerificationStore.put(context, "yt3", "src1", "track1", checked.copy(guardSeen = true))
        assertTrue(prefs.values["verified"].toString().endsWith("\tTitle\tArtist\twords"))
    }

    @Test
    fun `the cache is reloaded from disk after the in memory copy is dropped`() {
        val value = verified()
        HqVerificationStore.put(context, "yt1", "src1", "track1", value)

        cachedField.set(HqVerificationStore, null)

        assertEquals(value, HqVerificationStore.get(context, "yt1", "src1", "track1"))
        assertEquals(1, HqVerificationStore.entries(context).size)
    }

    @Test
    fun `an old line still loads while malformed lines are skipped`() {
        prefs.values["verified"] = listOf(
            "yt1\u0001src1\u0001track1\t500\t1.0\t90\tLOSSLESS_16",
            "short\t500\t1.0",
            "bad-offset\tnot-a-long\t1.0\t90\tLOSSLESS_16",
            "bad-rate\t500\tnot-a-double\t90\tLOSSLESS_16",
            "bad-match\t500\t1.0\tnot-an-int\tLOSSLESS_16",
            "bad-tier\t500\t1.0\t90\tNOT_A_TIER",
        ).joinToString("\n")
        prefs.values["rejected"] = "yt1\u0001src1\u0001track1\n\n   \nold-reject\u0001old-src\u0001old-track"

        assertEquals(
            HqVerificationStore.Verified(500L, 1.0, 90, HqTier.LOSSLESS_16),
            HqVerificationStore.get(context, "yt1", "src1", "track1"),
        )
        assertEquals(1, HqVerificationStore.entries(context).size)
        // Format 1 could not tell a failed decode from a mismatch, so its
        // rejections are dropped once rather than trusted as real verdicts.
        assertFalse(HqVerificationStore.isRejected(context, "yt1", "src1", "track1"))
        assertFalse(HqVerificationStore.isRejected(context, "old-reject", "old-src", "old-track"))
        assertEquals(2, prefs.values["version"])
        assertEquals(HqTier.LOSSLESS_16, HqVerificationStore.bestTier(context, "yt1"))
    }

    @Test
    fun `a rejection written by the current format survives a reload`() {
        HqVerificationStore.reject(context, "yt", "src", "track")

        assertEquals(2, prefs.values["version"])
        cachedField.set(HqVerificationStore, null)

        assertTrue(HqVerificationStore.isRejected(context, "yt", "src", "track"))
    }

    @Test
    fun `a key keeps its three parts but newlines and tabs become spaces`() {
        val value = verified()
        HqVerificationStore.put(context, "yt", "src", "track\nwith\ttabs", value)

        assertEquals(
            "yt\u0001src\u0001track with tabs\t1234\t1.001\t87\tLOSSLESS_16",
            prefs.values["verified"],
        )
        assertEquals(
            value,
            HqVerificationStore.get(context, "yt", "src", "track\nwith\ttabs"),
        )
        assertEquals(
            "track with tabs",
            HqVerificationStore.entries(context).single().trackId,
        )
    }

    @Test
    fun `entries need both a source and a track id`() {
        val value = verified()

        HqVerificationStore.put(context, "yt", null, "track", value)
        assertTrue(HqVerificationStore.entries(context).isEmpty())

        HqVerificationStore.put(context, "yt", "src", null, value)
        assertTrue(HqVerificationStore.entries(context).isEmpty())
    }

    @Test
    fun `best tier is the best rung across every pair of one video`() {
        HqVerificationStore.put(context, "yt", "src1", "t1", verified(HqTier.HIGH_LOSSY))
        HqVerificationStore.put(context, "yt", "src2", "t2", verified(HqTier.LOSSLESS_16))
        HqVerificationStore.put(context, "other", "src1", "t1", verified(HqTier.DOLBY_ATMOS))

        assertEquals(HqTier.LOSSLESS_16, HqVerificationStore.bestTier(context, "yt"))
        assertEquals(HqTier.DOLBY_ATMOS, HqVerificationStore.bestTier(context, "other"))
        assertNull(HqVerificationStore.bestTier(context, "missing"))
    }

    @Test
    fun `a rejection replaces the verification and a new verification clears the rejection`() {
        HqVerificationStore.put(context, "yt", "src", "track", verified())
        HqVerificationStore.reject(context, "yt", "src", "track")

        assertNull(HqVerificationStore.get(context, "yt", "src", "track"))
        assertTrue(HqVerificationStore.isRejected(context, "yt", "src", "track"))
        assertTrue(HqVerificationStore.entries(context).isEmpty())

        HqVerificationStore.put(context, "yt", "src", "track", verified())

        assertFalse(HqVerificationStore.isRejected(context, "yt", "src", "track"))
        assertEquals(verified(), HqVerificationStore.get(context, "yt", "src", "track"))
    }

    @Test
    fun `rejected lines survive a reload`() {
        HqVerificationStore.reject(context, "yt", "src", "track")

        cachedField.set(HqVerificationStore, null)

        assertTrue(HqVerificationStore.isRejected(context, "yt", "src", "track"))
    }

    @Test
    fun `invalidating a source drops only that source's verdicts`() {
        HqVerificationStore.put(context, "yt1", "src1", "track1", verified())
        HqVerificationStore.reject(context, "yt2", "src1", "track2")
        HqVerificationStore.put(context, "yt3", "src2", "track3", verified())

        HqVerificationStore.invalidateSources(context, setOf("src1"))

        assertNull(HqVerificationStore.get(context, "yt1", "src1", "track1"))
        assertFalse(HqVerificationStore.isRejected(context, "yt2", "src1", "track2"))
        assertEquals(verified(), HqVerificationStore.get(context, "yt3", "src2", "track3"))
        assertEquals(1, HqVerificationStore.entries(context).size)

        cachedField.set(HqVerificationStore, null)

        assertNull(HqVerificationStore.get(context, "yt1", "src1", "track1"))
        assertFalse(HqVerificationStore.isRejected(context, "yt2", "src1", "track2"))
        assertEquals(verified(), HqVerificationStore.get(context, "yt3", "src2", "track3"))
    }

    // Review 11-15 (H draft-3): the cache asks which rows were refused for a
    // song, to stop opening a copy one of them wrote before the refusal.
    @Test
    fun `refused rows are listed for their own song only`() {
        HqVerificationStore.reject(context, "yt1", "telegram", "4411")
        HqVerificationStore.reject(context, "yt1", "qobuz", "77")
        HqVerificationStore.reject(context, "yt10", "telegram", "9000")
        HqVerificationStore.put(context, "yt1", "telegram", "5000", verified())

        assertEquals(
            setOf("telegram" to "4411", "qobuz" to "77"),
            HqVerificationStore.rejectedRows(context, "yt1").toSet(),
        )
        assertEquals(listOf("telegram" to "9000"), HqVerificationStore.rejectedRows(context, "yt10"))
        assertTrue(HqVerificationStore.rejectedRows(context, "yt2").isEmpty())
        assertTrue(HqVerificationStore.rejectedRows(context, "").isEmpty())
    }

    // Review 11-17 (guard leftover): the search's risky mark lived in memory only,
    // so after a restart a play that verified without a search took a same-backing
    // remix on its sound and stored it as "guard seen", closing the words question.
    @Test
    fun `a risky mark survives a restart and a store written before it reads as not risky`() {
        HqVerificationStore.markRisky(context, "yt1", "telegram", "4411")
        assertEquals("yt1\u0001telegram\u00014411", prefs.values["risky"])

        cachedField.set(HqVerificationStore, null)

        assertTrue(HqVerificationStore.isRisky(context, "yt1", "telegram", "4411"))
        assertFalse(HqVerificationStore.isRisky(context, "yt1", "telegram", "5000"))
        assertFalse(HqVerificationStore.isRisky(context, "yt2", "telegram", "4411"))

        // An older build's store: no risky key at all, its verdict lines unchanged.
        prefs.values.remove("risky")
        prefs.values["verified"] = "yt3\u0001src1\u0001track1\t1234\t1.001\t87\tLOSSLESS_16\tTitle\tArtist\tseen"
        cachedField.set(HqVerificationStore, null)
        assertFalse(HqVerificationStore.isRisky(context, "yt3", "src1", "track1"))
        assertTrue(HqVerificationStore.get(context, "yt3", "src1", "track1")!!.guardSeen)
    }

    @Test
    fun `a risky mark takes back a guard seen verdict and leaves a words checked one`() {
        HqVerificationStore.put(context, "yt1", "src1", "track1", verified().copy(guardSeen = true))
        HqVerificationStore.put(context, "yt2", "src1", "track1", verified().copy(wordsChecked = true))

        HqVerificationStore.markRisky(context, "yt1", "src1", "track1")
        HqVerificationStore.markRisky(context, "yt2", "src1", "track1")
        cachedField.set(HqVerificationStore, null)

        val seen = HqVerificationStore.get(context, "yt1", "src1", "track1")!!
        assertFalse(seen.guardSeen)
        assertFalse(seen.wordsChecked)
        assertTrue(HqVerificationStore.get(context, "yt2", "src1", "track1")!!.wordsChecked)
    }

    @Test
    fun `risky marks need both ids and go with their source`() {
        HqVerificationStore.markRisky(context, "yt1", null, "track1")
        HqVerificationStore.markRisky(context, "yt1", "src1", "")
        assertNull(prefs.values["risky"])

        HqVerificationStore.markRisky(context, "yt1", "src1", "track1")
        HqVerificationStore.markRisky(context, "yt2", "src2", "track2")
        HqVerificationStore.invalidateSources(context, setOf("src1"))
        assertFalse(HqVerificationStore.isRisky(context, "yt1", "src1", "track1"))
        assertTrue(HqVerificationStore.isRisky(context, "yt2", "src2", "track2"))

        HqVerificationStore.invalidate(context, "src2")
        assertFalse(HqVerificationStore.isRisky(context, "yt2", "src2", "track2"))

        HqVerificationStore.markRisky(context, "yt3", "src3", "track3")
        HqVerificationStore.clear(context)
        cachedField.set(HqVerificationStore, null)
        assertFalse(HqVerificationStore.isRisky(context, "yt3", "src3", "track3"))
    }
}
