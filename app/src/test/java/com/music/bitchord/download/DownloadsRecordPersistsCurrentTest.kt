package com.music.bitchord.download

import android.content.SharedPreferences
import com.music.bitchord.testutil.FakePreferences
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Review D1: two workers finishing together. Worker A applies its update, then
 * waits for the persist lock while worker B adds its track and writes. Old
 * [Downloads.record] then wrote A's captured map — which lacks B — last, so
 * after a restart B's finished download was no longer recorded.
 */
class DownloadsRecordPersistsCurrentTest {

    private val prefsField = Downloads::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private val lockField = Downloads::class.java.getDeclaredField("recordLock").apply { isAccessible = true }

    private var originalPrefs: Any? = null
    private var originalSaved: Map<String, String> = emptyMap()
    private var originalMeta: Map<String, SavedSongMetadata> = emptyMap()
    private val fake = FakePreferences()

    @Before
    fun setUp() {
        originalSaved = Downloads.savedValue()
        originalMeta = Downloads.savedMetadataValue()
        originalPrefs = prefsField.get(Downloads)
        Downloads.setSaved(emptyMap())
        Downloads.setSavedMetadata(emptyMap())
        Downloads.prefs = fake.prefs
    }

    @After
    fun tearDown() {
        Downloads.setSaved(originalSaved)
        Downloads.setSavedMetadata(originalMeta)
        prefsField.set(Downloads, originalPrefs as SharedPreferences?)
    }

    @Test
    fun `the last persisted record holds both finished tracks`() {
        val lock = lockField.get(Downloads)!!
        val workerA: Thread
        synchronized(lock) {
            workerA = thread(name = "worker-a") {
                Downloads.record(saved = { it + ("track-a" to "file:///a.m4a") }, meta = { it })
            }
            // A has applied its compare-and-set update and now waits to persist.
            val deadline = System.nanoTime() + 10_000_000_000L
            while (workerA.state != Thread.State.BLOCKED) {
                if (!workerA.isAlive || System.nanoTime() > deadline) fail("worker A never reached the persist lock")
                Thread.sleep(1)
            }
            // B finishes meanwhile and persists first (the lock is reentrant here).
            Downloads.record(saved = { it + ("track-b" to "file:///b.m4a") }, meta = { it })
        }
        workerA.join(10_000)

        val persisted = fake.values["downloaded_tracks"] as String
        assertTrue("track A missing from $persisted", persisted.contains("track-a"))
        assertTrue("track B lost: an older snapshot was written last ($persisted)", persisted.contains("track-b"))
    }
}
