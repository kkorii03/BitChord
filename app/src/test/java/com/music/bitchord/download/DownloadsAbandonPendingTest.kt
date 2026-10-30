package com.music.bitchord.download

import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.HqTier
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Taking back a queued row must not clear the overrides of a row that is no
 * longer pending because a worker already claimed it. An in-flight replacement
 * still needs its pinned source and tier to fetch the right recording.
 */
class DownloadsAbandonPendingTest {

    private val videoId = "video-1"

    private val pendingField = field("pending")
    private val qualityField = field("qualityOverrides")
    private val hqField = field("hqOverrides")
    private val avoidField = field("avoidSources")
    private val forceReplaceField = field("forceReplaceIds")
    private val youtubeOnlyField = field("youtubeOnlyIds")
    private val minimumTierField = field("minimumTiers")
    private val automaticField = field("automaticIds")
    private val mvRepairField = field("mvSoundRepairIds")

    @Suppress("UNCHECKED_CAST")
    private val pending = pendingField.get(Downloads) as MutableMap<String, Any>

    @Before
    fun setUp() {
        pending.clear()
        listOf(
            qualityField,
            hqField,
            avoidField,
            forceReplaceField,
            youtubeOnlyField,
            minimumTierField,
            automaticField,
            mvRepairField,
        ).forEach { clearMap(it) }
    }

    @After
    fun tearDown() {
        pending.clear()
        listOf(
            qualityField,
            hqField,
            avoidField,
            forceReplaceField,
            youtubeOnlyField,
            minimumTierField,
            automaticField,
            mvRepairField,
        ).forEach { clearMap(it) }
    }

    @Test
    fun `a claimed row keeps its overrides when it is no longer pending`() {
        put(qualityField, DownloadQuality.LOSSLESS)
        put(
            hqField,
            HqDownloadSource(
                sourceConfigId = "source",
                trackId = "track",
                tier = HqTier.LOSSLESS_16,
            ),
        )
        put(avoidField, "avoid")
        add(forceReplaceField)
        add(youtubeOnlyField)
        put(minimumTierField, HqTier.HI_RES_24_44)
        add(automaticField)
        add(mvRepairField)

        val abandon = Downloads::class.java.getDeclaredMethod(
            "abandonPending",
            String::class.java,
        ).apply { isAccessible = true }

        assertFalse(abandon.invoke(Downloads, videoId) as Boolean)
        assertTrue(contains(qualityField))
        assertTrue(contains(hqField))
        assertTrue(contains(avoidField))
        assertTrue(contains(forceReplaceField))
        assertTrue(contains(youtubeOnlyField))
        assertTrue(contains(minimumTierField))
        assertTrue(contains(automaticField))
        assertTrue(contains(mvRepairField))
    }

    private fun field(name: String) = Downloads::class.java
        .getDeclaredField(name)
        .apply { isAccessible = true }

    private fun clearMap(field: java.lang.reflect.Field) {
        when (val value = field.get(Downloads)) {
            is MutableMap<*, *> -> value.clear()
            is MutableSet<*> -> value.clear()
            else -> error("unexpected field type ${value?.javaClass}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun put(field: java.lang.reflect.Field, value: Any) {
        (field.get(Downloads) as MutableMap<String, Any>)[videoId] = value
    }

    @Suppress("UNCHECKED_CAST")
    private fun add(field: java.lang.reflect.Field) {
        (field.get(Downloads) as MutableSet<String>).add(videoId)
    }

    @Suppress("UNCHECKED_CAST")
    private fun contains(field: java.lang.reflect.Field): Boolean = when (val value = field.get(Downloads)) {
        is Map<*, *> -> value.containsKey(videoId)
        is Set<*> -> value.contains(videoId)
        else -> error("unexpected field type ${value?.javaClass}")
    }
}
