package com.music.bitchord.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A run summary must survive a process death without double-counting.
 *
 * [HqLibraryUpgrade] persists the per-run failed count, but used to clear the
 * dedup key set on reload. The same target then registered itself again in the
 * continuation of the same run and the finished line counted one song twice.
 * A target that later succeeds must also leave the per-run summary.
 */
class HqLibraryUpgradeRunSummaryPersistenceTest {

    private val failedField = HqLibraryUpgrade::class.java
        .getDeclaredField("failedThisRun")
        .apply { isAccessible = true }
    private val keysField = HqLibraryUpgrade::class.java
        .getDeclaredField("failedRunKeys")
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val keys = keysField.get(HqLibraryUpgrade) as MutableSet<String>

    @After
    fun tearDown() {
        reset()
    }

    @Test
    fun `a target that already failed before a restart is not counted again`() {
        restore(persistedFailed = 2, persistedKeys = listOf(KEY_A, KEY_B))

        assertEquals(2, failed())
        register(KEY_A)

        assertEquals(2, failed())
        assertEquals(setOf(KEY_A, KEY_B), keys.toSet())
    }

    @Test
    fun `a target that later succeeds leaves the per-run failure summary`() {
        restore(persistedFailed = 1, persistedKeys = listOf(KEY_A))

        clear(KEY_A)

        assertEquals(0, failed())
        assertFalse(keys.contains(KEY_A))
    }

    @Test
    fun `clearing an unregistered key does not go below zero or move the set`() {
        restore(persistedFailed = 1, persistedKeys = listOf(KEY_A))

        clear(KEY_B)

        assertEquals(1, failed())
        assertTrue(keys.contains(KEY_A))
        assertFalse(keys.contains(KEY_B))
    }

    @Test
    fun `a legacy count without stored keys is kept`() {
        restore(persistedFailed = 3, persistedKeys = emptyList())

        assertEquals(3, failed())
        assertTrue(keys.isEmpty())
    }

    private fun failed(): Int = failedField.getInt(HqLibraryUpgrade)

    private fun restore(persistedFailed: Int, persistedKeys: List<String>) {
        val method = HqLibraryUpgrade::class.java.getDeclaredMethod(
            "restoreRunSummaryLocked",
            Int::class.javaPrimitiveType,
            Collection::class.java,
        ).apply { isAccessible = true }
        method.invoke(HqLibraryUpgrade, persistedFailed, persistedKeys)
    }

    private fun register(key: String) {
        HqLibraryUpgrade::class.java
            .getDeclaredMethod("registerRunFailureLocked", String::class.java)
            .apply { isAccessible = true }
            .invoke(HqLibraryUpgrade, key)
    }

    private fun clear(key: String) {
        HqLibraryUpgrade::class.java
            .getDeclaredMethod("clearRunFailureLocked", String::class.java)
            .apply { isAccessible = true }
            .invoke(HqLibraryUpgrade, key)
    }

    private fun reset() {
        HqLibraryUpgrade::class.java
            .getDeclaredMethod("resetRunSummaryLocked")
            .apply { isAccessible = true }
            .invoke(HqLibraryUpgrade)
    }

    private companion object {
        const val KEY_A = "video-1\u0002source\u0002track"
        const val KEY_B = "video-2\u0002source\u0002track"
    }
}
