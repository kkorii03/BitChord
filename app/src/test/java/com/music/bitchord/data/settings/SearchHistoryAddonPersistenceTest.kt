package com.music.bitchord.data.settings

import android.content.Context
import com.music.bitchord.testutil.FakeContext
import com.music.bitchord.testutil.FakePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The Addons search history is written from the UI before the deferred startup
 * open has necessarily run. Reading the in-memory list at the call site and
 * opening afterwards made the first search after launch overwrite whatever was
 * on disk with only the new term.
 */
class SearchHistoryAddonPersistenceTest {

    private lateinit var preferences: FakePreferences

    private val appContextField = field("appContext")
    private val openedField = field("opened")
    private val addonRecentField = field("_addonRecent")
    private val writerField = field("writer")

    @Before
    fun setUp() {
        preferences = FakePreferences()
        val context: Context = FakeContext.create(preferences)
        appContextField.set(SearchHistory, context)
        openedField.set(SearchHistory, false)
        addonRecentOf(SearchHistory).value = emptyList()
    }

    @Test
    fun `the first addon search after launch keeps the stored history`() {
        preferences.values[KEY_ADDON_HISTORY] = "[\"old query\"]"

        SearchHistory.recordAddonQuery("new query")
        drainWriter()

        assertEquals(listOf("new query", "old query"), SearchHistory.addonRecent.value)
    }

    @Test
    fun `removing an addon query before the store opens keeps the rest`() {
        preferences.values[KEY_ADDON_HISTORY] = "[\"newer\",\"old query\"]"

        SearchHistory.removeAddonQuery("old query")
        drainWriter()

        assertEquals(listOf("newer"), SearchHistory.addonRecent.value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun addonRecentOf(history: SearchHistory): MutableStateFlow<List<String>> =
        addonRecentField.get(history) as MutableStateFlow<List<String>>

    private fun drainWriter() {
        val done = CountDownLatch(1)
        (writerField.get(SearchHistory) as Executor).execute { done.countDown() }
        assertTrue("search history writer did not finish", done.await(2, TimeUnit.SECONDS))
    }

    private fun field(name: String) =
        SearchHistory::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        const val KEY_ADDON_HISTORY = "addon_search_history"
    }
}
