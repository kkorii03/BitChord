package com.music.bitchord.download

import android.content.SharedPreferences
import com.music.bitchord.startup.AppForeground
import com.music.bitchord.testutil.FakePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review D4: a like handed to the queue while the app has no window (the
 * notification heart, or the startup drain in a process the playback service
 * started) only sits in memory, because the queue does not start without a
 * window. Old code unparked it unconditionally right after the enqueue, so
 * process death lost the like for good.
 */
class LikedAutoDownloadKeepsBackgroundLikeTest {

    private val prefsField = LikedAutoDownload::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private val settle = LikedAutoDownload::class.java
        .getDeclaredMethod("settle", String::class.java)
        .apply { isAccessible = true }
    private val unparkSaved = LikedAutoDownload::class.java
        .getDeclaredMethod("unparkSaved", Set::class.java)
        .apply { isAccessible = true }

    private val foregroundField = AppForeground::class.java.getDeclaredField("isForeground").apply { isAccessible = true }

    private var originalPrefs: Any? = null
    private var originalSaved: Map<String, String> = emptyMap()
    private var originalForeground = false
    private val fake = FakePreferences()

    @Before
    fun setUp() {
        originalPrefs = prefsField.get(LikedAutoDownload)
        originalSaved = Downloads.savedValue()
        originalForeground = AppForeground.isForeground
        foregroundField.setBoolean(AppForeground, false)
        Downloads.setSaved(emptyMap())
        prefsField.set(LikedAutoDownload, fake.prefs)
    }

    @After
    fun tearDown() {
        Downloads.setSaved(originalSaved)
        prefsField.set(LikedAutoDownload, originalPrefs as SharedPreferences?)
        foregroundField.setBoolean(AppForeground, originalForeground)
    }

    private fun parked(): List<String> =
        (fake.values["liked_download_pending"] as? String).orEmpty().split('\n').filter { it.isNotBlank() }

    @Test
    fun `a like enqueued without a window stays on the on-disk list`() {
        fake.values["liked_download_pending"] = "liked-1"

        settle.invoke(LikedAutoDownload, "liked-1")
        settle.invoke(LikedAutoDownload, "liked-2")

        assertEquals(listOf("liked-1", "liked-2"), parked())
    }

    @Test
    fun `a saved like leaves the list`() {
        fake.values["liked_download_pending"] = "liked-1\nliked-2"
        Downloads.setSaved(mapOf("liked-1" to "file:///a.m4a"))

        settle.invoke(LikedAutoDownload, "liked-1")
        assertEquals(listOf("liked-2"), parked())

        unparkSaved.invoke(LikedAutoDownload, setOf("liked-2"))
        assertEquals(emptyList<String>(), parked())
    }

    @Test
    fun `the decision`() {
        assertTrue(LikedAutoDownload.keepParked(saved = false, foreground = false))
        assertFalse(LikedAutoDownload.keepParked(saved = false, foreground = true))
        assertFalse(LikedAutoDownload.keepParked(saved = true, foreground = false))
        assertEquals(listOf("b"), LikedAutoDownload.withoutSaved(listOf("a", "b"), setOf("a", "c")))
    }
}
