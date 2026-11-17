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
 * every process start and posted a new failure row each time, for ever. Three
 * real failures now take it off the list (one network blip does not); a
 * refusal that never tried keeps it, and a save clears the count.
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

    private val unparkSaved = LikedAutoDownload::class.java
        .getDeclaredMethod("unparkSaved", Set::class.java)
        .apply { isAccessible = true }

    private fun parked(): List<String> =
        (fake.values["liked_download_pending"] as? String).orEmpty().split('\n').filter { it.isNotBlank() }

    private fun failures(): Map<String, Int> =
        LikedAutoDownload.decodeFailures(fake.values["liked_download_failures"] as? String)

    /** One failed download of [id], as the queue's state flow reports it. */
    private fun failOnce(id: String) {
        val newly = LikedAutoDownload.newlyFailed(emptyMap(), mapOf(id to DownloadState.Failed("network")))
        unparkFailed.invoke(LikedAutoDownload, newly)
    }

    @Test
    fun `one failure keeps the like parked`() {
        fake.values["liked_download_pending"] = "blip-1\nok-2"

        failOnce("blip-1")

        assertEquals(listOf("blip-1", "ok-2"), parked())
        assertEquals(mapOf("blip-1" to 1), failures())
    }

    @Test
    fun `the third failure takes the like off the list`() {
        fake.values["liked_download_pending"] = "gone-1\nok-2"

        repeat(LikedAutoDownload.MAX_FAILURES - 1) { failOnce("gone-1") }
        assertEquals(listOf("gone-1", "ok-2"), parked())

        failOnce("gone-1")
        assertEquals(listOf("ok-2"), parked())
        assertEquals(emptyMap<String, Int>(), failures())
    }

    @Test
    fun `a save clears the failure count`() {
        fake.values["liked_download_pending"] = "late-1\nok-2"
        failOnce("late-1")
        failOnce("late-1")

        unparkSaved.invoke(LikedAutoDownload, setOf("late-1"))

        assertEquals(listOf("ok-2"), parked())
        assertEquals(emptyMap<String, Int>(), failures())
    }

    @Test
    fun `a failure of an id that is not parked is not counted`() {
        fake.values["liked_download_pending"] = "ok-2"
        failOnce("other")
        assertEquals(emptyMap<String, Int>(), failures())
    }

    @Test
    fun `failure counts read back and ignore damaged lines`() {
        val counts = mapOf("a:b" to 2, "c" to 1)
        assertEquals(counts, LikedAutoDownload.decodeFailures(LikedAutoDownload.encodeFailures(counts)))
        assertEquals(mapOf("ok" to 2), LikedAutoDownload.decodeFailures("ok:2\nbad\n:3\nzero:0\nx:y"))
        assertEquals(emptyMap<String, Int>(), LikedAutoDownload.decodeFailures(null))
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
