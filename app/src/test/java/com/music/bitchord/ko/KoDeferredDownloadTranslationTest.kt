package com.music.bitchord.ko

import android.content.Context
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.TranslationAhead
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Review RG3-5: a download finished during power saving skipped its kept
 * translation (X8) and nothing caught it up later, so a playlist downloaded on
 * battery saver for a flight had no translation offline. The id is now kept
 * on disk for the power-saving-ended and next-play catch-up.
 */
class KoDeferredDownloadTranslationTest {

    private lateinit var prefs: FakePreferences
    private lateinit var context: Context
    private var keepBefore = true
    private var lowPowerBefore = false

    private val song = Song(
        videoId = "dlVideo0001",
        title = "Song",
        artist = "Artist",
        thumbnailUrl = null,
        localUri = "file:///tmp/song.m4a",
    )

    @Before
    fun setUp() {
        prefs = FakePreferences()
        context = FakeContext.create(prefs)
        keepBefore = KoAppSettings.keepDownloadTranslation.value
        lowPowerBefore = KoAppSettings.lowPower.value
    }

    @After
    fun tearDown() {
        KoAppSettings.keepDownloadTranslation.value = keepBefore
        KoAppSettings.lowPower.value = lowPowerBefore
    }

    @Test
    fun `a download in power saving is remembered for later`() {
        KoAppSettings.keepDownloadTranslation.value = true
        KoAppSettings.lowPower.value = true

        TranslationAhead.downloaded(context, song, song.localUri!!)
        TranslationAhead.downloaded(context, song, song.localUri!!)

        assertEquals(listOf("dlVideo0001"), KoDeferredDownloadTranslation.deferred(context))
        assertEquals("dlVideo0001", prefs.values[KoDeferredDownloadTranslation.KEY])
    }

    @Test
    fun `the setting off remembers nothing`() {
        KoAppSettings.keepDownloadTranslation.value = false
        KoAppSettings.lowPower.value = true

        TranslationAhead.downloaded(context, song, song.localUri!!)

        assertEquals(emptyList<String>(), KoDeferredDownloadTranslation.deferred(context))
    }

    @Test
    fun `the list is capped, oldest first out`() {
        assertEquals(listOf("b", "c"), KoDeferredDownloadTranslation.withDeferred(listOf("a", "b"), "c", cap = 2))
        assertEquals(listOf("a", "b"), KoDeferredDownloadTranslation.withDeferred(listOf("a", "b"), "a", cap = 2))
    }
}
