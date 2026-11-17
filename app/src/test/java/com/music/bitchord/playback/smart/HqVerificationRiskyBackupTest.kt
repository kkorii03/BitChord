package com.music.bitchord.playback.smart

import android.content.Context
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review 11-17 (RG5-4). The device-migration backup carried the verified and
 * rejected rows but not the risky marks, so on the new phone a restored
 * chroma-only verdict answered for a pair whose search had shown another
 * version of the song, and the words check was never asked.
 */
class HqVerificationRiskyBackupTest {

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

    @Test
    fun `the risky marks go with the backup and come back`() {
        HqVerificationStore.markRisky(context, "yt1", "telegram", "4411")
        val exported = HqVerificationStore.exportBackupRows(context)

        // The new phone: nothing of this store yet.
        prefs.values.clear()
        cachedField.set(HqVerificationStore, null)
        assertFalse(HqVerificationStore.isRisky(context, "yt1", "telegram", "4411"))

        assertTrue(HqVerificationStore.restoreBackupRows(context, exported.first, exported.second, exported.third))
        assertTrue(HqVerificationStore.isRisky(context, "yt1", "telegram", "4411"))
    }

    @Test
    fun `an older backup without marks keeps the marks already here`() {
        HqVerificationStore.markRisky(context, "yt2", "src", "trk")
        assertTrue(HqVerificationStore.restoreBackupRows(context, verified = null, rejected = null))
        assertTrue(HqVerificationStore.isRisky(context, "yt2", "src", "trk"))
        // A rollback to "none before" clears them.
        assertTrue(HqVerificationStore.restoreBackupRows(context, null, null, ""))
        assertFalse(HqVerificationStore.isRisky(context, "yt2", "src", "trk"))
    }

    @Test
    fun `export, import and rollback all carry the marks`() {
        val root = File(".").canonicalFile
        val backup = listOf("app/", "").map { File(root, "${it}src/main/java/com/music/bitchord/ko/KoBackup.kt") }
            .first { it.exists() }.readText()
        assertTrue(backup.contains("hqRisky = HqVerificationStore.exportBackupRows(context).third"))
        assertTrue(backup.contains("file.hqRisky,"))
        assertTrue(backup.contains("previousHq.third.orEmpty(),"))
        assertEquals(1, Regex("""val hqRisky: String\? = null""").findAll(backup).count())
    }
}
