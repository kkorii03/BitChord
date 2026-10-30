package com.music.bitchord.data.settings

import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A record queued just before a restore must be written before it, not after;
 * otherwise the old search list replaces the one the import just committed.
 */
class SearchHistoryImportOrderingTest {

    private val prefsField = field("prefs")
    private val openedField = field("opened")
    private val writerField = field("writer")
    private val recentField = field("_recent")

    private lateinit var preferences: FakePreferences
    private var originalPrefs: Any? = null
    private var originalOpened = false
    private var originalWriter: Any? = null

    @Before
    fun setUp() {
        preferences = FakePreferences()
        originalPrefs = prefsField.get(SearchHistory)
        originalOpened = openedField.getBoolean(SearchHistory)
        originalWriter = writerField.get(SearchHistory)
        prefsField.set(SearchHistory, preferences.prefs)
        openedField.setBoolean(SearchHistory, true)
    }

    @After
    fun tearDown() {
        originalPrefs?.let { prefsField.set(SearchHistory, it) }
        openedField.setBoolean(SearchHistory, originalOpened)
        originalWriter?.let { setStatic(writerField, it) }
        @Suppress("UNCHECKED_CAST")
        (recentField.get(SearchHistory) as MutableStateFlow<List<SearchHistoryEntity>>).value = emptyList()
    }

    @Test
    fun `a save queued before a replace cannot overwrite the restored history`() = runBlocking {
        val executor = PausedExecutor()
        setStatic(writerField, executor)

        SearchHistory.record(entity("stale", "Stale"))
        val result = async(Dispatchers.Default) {
            SearchHistory.replace(listOf(entity("restored", "Restored")))
        }
        // Let the replace queue behind the pending save, then run both.
        delay(50)
        executor.drain()
        assertTrue(result.await())

        val raw = preferences.values[SearchHistory.KEY_HISTORY] as String
        assertTrue("the restored history must win", raw.contains("restored"))
        assertFalse("a save from before the import must not land afterwards", raw.contains("stale"))
    }

    private fun entity(id: String, title: String) = SearchHistoryEntity(
        id = id,
        title = title,
        subtitle = title,
        artworkUrl = null,
        entityType = EntityType.TRACK,
        timestamp = 0L,
    )

    private fun field(name: String) =
        SearchHistory::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun setStatic(field: Field, value: Any?) {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, field)
        unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(unsafe, base, offset, value)
    }

    private class PausedExecutor : AbstractExecutorService() {
        private val queued = LinkedBlockingQueue<Runnable>()

        override fun execute(command: Runnable) {
            queued.add(command)
        }

        fun drain() {
            while (true) {
                val next = queued.poll() ?: return
                next.run()
            }
        }

        override fun shutdown() = Unit

        override fun shutdownNow(): MutableList<Runnable> = queued.toMutableList()

        override fun isShutdown(): Boolean = false

        override fun isTerminated(): Boolean = false

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }
}
