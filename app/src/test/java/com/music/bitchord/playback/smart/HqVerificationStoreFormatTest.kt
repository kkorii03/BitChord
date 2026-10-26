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
        assertTrue(HqVerificationStore.isRejected(context, "yt1", "src1", "track1"))
        assertTrue(HqVerificationStore.isRejected(context, "old-reject", "old-src", "old-track"))
        assertEquals(HqTier.LOSSLESS_16, HqVerificationStore.bestTier(context, "yt1"))
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
}
