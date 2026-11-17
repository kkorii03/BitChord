package com.music.bitchord.download

import android.content.SharedPreferences
import com.music.bitchord.testutil.FakePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Review RG3-4: a like parked without a window (D4) left the on-disk list only
 * once its file was saved. A download that failed for good was queued again at
 * every process start and posted a new failure row each time, for ever. A real
 * failure now takes it off the list; a refusal that never tried keeps it.
 */
class LikedAutoDownloadDropsFailedLikeTest {

    private val prefsField = LikedAutoDownload::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private val unparkFailed = LikedAutoDownload::class.java
        .getDeclaredMethod("unparkFailed", Set::class.java)
        .apply { isAccessible = true }

    private var originalPrefs: Any? = null
    private val fake = FakePreferences()

    @Before
    fun setUp() {
        originalPrefs = prefsField.get(LikedAutoDownload)
        prefsField.set(LikedAutoDownload, fake.prefs)
    }

    @After
    fun tearDown() {
        prefsField.set(LikedAutoDownload, originalPrefs as SharedPreferences?)
    }

    private fun parked(): List<String> =
        (fake.values["liked_download_pending"] as? String).orEmpty().split('\n').filter { it.isNotBlank() }

    @Test
    fun `a parked like whose download failed leaves the list`() {
        fake.values["liked_download_pending"] = "gone-1\nok-2"
        val failed = DownloadState.Failed("삭제된 동영상")

        val newly = LikedAutoDownload.newlyFailed(emptyMap(), mapOf("gone-1" to failed, "ok-2" to DownloadState.Queued))
        unparkFailed.invoke(LikedAutoDownload, newly)

        assertEquals(listOf("ok-2"), parked())
    }

    @Test
    fun `a refusal that never tried keeps the like parked`() {
        val now = mapOf(
            "wifi" to DownloadState.Failed(Downloads.WIFI_ONLY_REFUSAL),
            "service" to DownloadState.Failed(Downloads.SERVICE_START_REFUSAL),
        )
        assertEquals(emptySet<String>(), LikedAutoDownload.newlyFailed(emptyMap(), now))
    }

    @Test
    fun `an old failure is not acted on again when another row changes`() {
        val failed = DownloadState.Failed("x")
        val before = mapOf("old" to failed)
        val now = mapOf("old" to failed, "other" to DownloadState.Queued)
        assertEquals(emptySet<String>(), LikedAutoDownload.newlyFailed(before, now))
        // A new failure of the same id (a retry that failed again) is a new instance.
        assertEquals(setOf("old"), LikedAutoDownload.newlyFailed(before, mapOf("old" to DownloadState.Failed("x"))))
    }
}
