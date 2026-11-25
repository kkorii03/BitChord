package com.music.bitchord.download

import android.content.Context
import com.music.bitchord.data.sources.HqTier
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.playback.smart.HqVerificationStore
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
 * 11-18 phone: the library upgrade heard and queued a pair from kkorii12,
 * whose manifest said `allowDownloads: 0`, and the download failed in 18 ms
 * ("검증된 고음질 음원을 받을 수 없습니다"); the pair was planned again after the
 * cooldown. It also chose that source's Dolby Atmos row, which the automatic
 * playback look passes over as not lossless.
 */
class HqLibraryDownloadSourceTest {

    private lateinit var context: Context
    private val cachedField = HqVerificationStore::class.java.getDeclaredField("cached").apply {
        isAccessible = true
    }

    @Before
    fun setUp() {
        context = FakeContext.create(FakePreferences())
        cachedField.set(HqVerificationStore, null)
    }

    @After
    fun tearDown() {
        cachedField.set(HqVerificationStore, null)
    }

    private fun verified(tier: HqTier) = HqVerificationStore.Verified(
        offsetMs = 0L,
        rate = 1.0,
        matchPercent = 100,
        tier = tier,
        wordsChecked = true,
        printChecked = true,
    )

    @Test
    fun `a pair from a source that allows no downloads is not a library download`() {
        val downloadable = setOf("jiosaavn", "telegram")

        assertFalse(HqLibraryUpgrade.libraryCanFetch("kkorii12", HqTier.HI_RES_24_44, downloadable, automatic = true))
        assertFalse(HqLibraryUpgrade.libraryCanFetch("kkorii12", HqTier.HI_RES_24_44, downloadable, automatic = false))
        assertTrue(HqLibraryUpgrade.libraryCanFetch("telegram", HqTier.HI_RES_24_44, downloadable, automatic = true))
    }

    @Test
    fun `the automatic pass leaves Atmos out and a request by hand keeps it`() {
        val downloadable = setOf("telegram")

        assertFalse(HqLibraryUpgrade.libraryCanFetch("telegram", HqTier.DOLBY_ATMOS, downloadable, automatic = true))
        assertTrue(HqLibraryUpgrade.libraryCanFetch("telegram", HqTier.DOLBY_ATMOS, downloadable, automatic = false))
    }

    @Test
    fun `the file check counts only a better pair a download could fetch`() {
        // AQUrMavKqVg: the only pair above the saved 320 kbps file is the
        // no-downloads source's Atmos row. The old check read the store's best
        // rung, so the file was "lower quality" on every play.
        HqVerificationStore.put(context, "AQUrMavKqVg", "kkorii12", "t_atmos", verified(HqTier.DOLBY_ATMOS))
        HqVerificationStore.put(context, "AQUrMavKqVg", "kkorii12", "t_flac", verified(HqTier.HI_RES_24_44))
        HqVerificationStore.put(context, "AQUrMavKqVg", "telegram", "t_atmos", verified(HqTier.DOLBY_ATMOS))
        val entries = HqVerificationStore.entries(context)

        assertEquals(HqTier.DOLBY_ATMOS, HqVerificationStore.bestTier(context, "AQUrMavKqVg"))
        assertNull(HqLibraryUpgrade.libraryBestTier(entries, "AQUrMavKqVg", setOf("telegram"), automatic = true))

        HqVerificationStore.put(context, "AQUrMavKqVg", "telegram", "t_flac", verified(HqTier.LOSSLESS_16))
        assertEquals(
            HqTier.LOSSLESS_16,
            HqLibraryUpgrade.libraryBestTier(HqVerificationStore.entries(context), "AQUrMavKqVg", setOf("telegram"), automatic = true),
        )
        assertNull(HqLibraryUpgrade.libraryBestTier(entries, "other-song", setOf("telegram"), automatic = true))
    }

    @Test
    fun `the upgrade search skips the sources a download may not use but never YouTube`() {
        val active = listOf(
            "kkorii12" to SourceKind.ADDON,
            "trial" to SourceKind.ADDON,
            "telegram" to SourceKind.TELEGRAM,
            "youtube" to SourceKind.YOUTUBE,
        )

        assertEquals(
            setOf("kkorii12", "trial"),
            HqLibraryUpgrade.searchSkipsForDownload(active, downloadSources = setOf("telegram")),
        )
        assertEquals(
            setOf("kkorii12", "trial", "telegram"),
            HqLibraryUpgrade.searchSkipsForDownload(active, downloadSources = emptySet()),
        )
    }
}
