package com.music.bitchord.playback.smart

import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * A verified pair names the source config it was measured against. Editing or
 * replacing that source has to forget the old verdict, or a later download asks
 * the new server for the old catalogue's track id.
 */
class HqVerificationStoreInvalidationTest {

    private val context = FakeContext.create(
        FakePreferences(),
        Files.createTempDirectory("bitchord-hq-verify").toFile(),
    )

    private fun verified() = HqVerificationStore.Verified(
        offsetMs = 0,
        rate = 1.0,
        matchPercent = 90,
        tier = HqTier.LOSSLESS_16,
    )

    @Test
    fun `invalidating one source keeps the other source's verdicts`() {
        HqVerificationStore.put(context, "yt-a", "source-a", "track-a", verified())
        HqVerificationStore.put(context, "yt-b", "source-b", "track-b", verified())

        HqVerificationStore.invalidate(context, "source-a")

        val ids = HqVerificationStore.entries(context).map { it.sourceConfigId }
        assertEquals(listOf("source-b"), ids)
    }

    @Test
    fun `clearing forgets every accepted and rejected pair`() {
        HqVerificationStore.put(context, "yt-a", "source-a", "track-a", verified())
        HqVerificationStore.reject(context, "yt-b", "source-b", "track-b")

        HqVerificationStore.clear(context)

        assertTrue(HqVerificationStore.entries(context).isEmpty())
        assertTrue(!HqVerificationStore.isRejected(context, "yt-b", "source-b", "track-b"))
    }
}
