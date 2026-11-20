package com.music.bitchord.ko

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import java.lang.reflect.Proxy
import java.util.TreeSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single purge rule: v1.9's keepsAddonCopy and keepAnalysis, plus the
 * playhead/timing keeps, now shared by the purge index and the drop pass.
 */
class KoCacheRetentionTest {

    private fun drop(
        origin: String? = "YOUTUBE",
        hasAnalysis: Boolean = false,
        hasPlayback: Boolean = false,
        hasPlayed: Boolean = false,
        keepAnalysis: Boolean = false,
        keepsAddonCopy: Boolean = false,
        keptByQueueOrTiming: Boolean = false,
        keptForAnalysis: Boolean = false,
    ) = cacheEntryShouldDrop(
        origin = origin,
        foreignOrigin = "OTHER",
        hasAnalysis = hasAnalysis,
        hasPlayback = hasPlayback,
        hasPlayed = hasPlayed,
        keepAnalysis = keepAnalysis,
        keepsAddonCopy = keepsAddonCopy,
        keptByQueueOrTiming = keptByQueueOrTiming,
        keptForAnalysis = keptForAnalysis,
    )

    // 11-08 phone log: every repeat analysis round (79) came after a drop pass
    // had removed the copy the analysis had just fetched, and downloaded it again.
    @Test
    fun `an analysis copy asked for recently survives the drop pass`() {
        assertFalse(drop(origin = "YOUTUBE", hasAnalysis = true, keptForAnalysis = true))
    }

    @Test
    fun `the analysis keep never spares a partial addon copy`() {
        assertTrue(drop(origin = "OTHER", keepsAddonCopy = false, keptForAnalysis = true))
        assertTrue(drop(origin = "OTHER", hasAnalysis = true, keepsAddonCopy = false, keptForAnalysis = true))
    }

    @Test
    fun `the analysis keep lasts ten minutes from the last ask`() {
        val retention = KoCacheRetention()
        assertFalse(retention.keptForAnalysis("a", nowMs = 0L))
        retention.keepForAnalysis("a", nowMs = 1_000L)
        assertTrue(retention.keptForAnalysis("a", nowMs = 1_000L + 9 * 60_000L))
        assertFalse(retention.keptForAnalysis("a", nowMs = 1_000L + 10 * 60_000L))
        retention.keepForAnalysis("a", nowMs = 1_000L + 10 * 60_000L)
        assertTrue(retention.keptForAnalysis("a", nowMs = 1_000L + 15 * 60_000L))
    }

    @Test
    fun `the analysis keep holds only the most recent twelve tracks`() {
        val retention = KoCacheRetention()
        (0 until 13).forEach { retention.keepForAnalysis("t$it", nowMs = it.toLong()) }
        assertFalse(retention.keptForAnalysis("t0", nowMs = 20L))
        assertTrue(retention.keptForAnalysis("t1", nowMs = 20L))
        assertTrue(retention.keptForAnalysis("t12", nowMs = 20L))
    }

    @Test
    fun `a complete addon copy is kept`() {
        assertFalse(drop(origin = "OTHER", keepsAddonCopy = true))
    }

    @Test
    fun `an incomplete addon copy is dropped`() {
        assertTrue(drop(origin = "OTHER", keepsAddonCopy = false))
    }

    @Test
    fun `analysis-only copies are kept when the caller asked to keep analysis`() {
        assertFalse(drop(origin = "YOUTUBE", hasAnalysis = true, keepAnalysis = true))
    }

    @Test
    fun `analysis-only copies are dropped when analysis is not kept`() {
        assertTrue(drop(origin = "YOUTUBE", hasAnalysis = true, keepAnalysis = false))
    }

    @Test
    fun `an analysis copy playback has read is not analysis-only`() {
        assertFalse(drop(origin = "YOUTUBE", hasAnalysis = true, hasPlayback = true))
        assertFalse(drop(origin = "YOUTUBE", hasAnalysis = true, hasPlayed = true))
    }

    @Test
    fun `the playhead window or a timing keep spares every candidate`() {
        assertFalse(drop(origin = "OTHER", keepsAddonCopy = false, keptByQueueOrTiming = true))
        assertFalse(drop(origin = "YOUTUBE", hasAnalysis = true, keptByQueueOrTiming = true))
    }

    /** A cache of one addon entry "id1" that records what the drop pass removes. */
    private fun addonCache(removed: MutableList<String>): Cache {
        val values = mapOf("bc_origin" to "OTHER", "bc_media_id" to "id1")
        val meta = Proxy.newProxyInstance(
            ContentMetadata::class.java.classLoader,
            arrayOf(ContentMetadata::class.java),
        ) { _, method, args ->
            when {
                method.name == "get" && method.returnType == String::class.java -> values[args!![0] as String] ?: args[1]
                method.name == "contains" -> (args!![0] as String) in values
                else -> args?.getOrNull(1)
            }
        } as ContentMetadata
        return Proxy.newProxyInstance(
            Cache::class.java.classLoader,
            arrayOf(Cache::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getKeys" -> TreeSet(listOf("id1"))
                "getContentMetadata" -> meta
                "removeResource" -> { removed += args!![0] as String; null }
                else -> null
            }
        } as Cache
    }

    private fun dropPass(keep: Set<String>, keepsAddonCopy: Boolean): List<String> {
        val removed = mutableListOf<String>()
        KoCacheRetention().dropForeignEntries(
            cache = addonCache(removed),
            keep = keep,
            keepAnalysis = false,
            keepsAddonCopy = { keepsAddonCopy },
            scope = CoroutineScope(Dispatchers.Unconfined),
            onDropped = {},
        )
        return removed
    }

    // P2-0: the pass removed exactly what the rule says to keep, and kept the rest.
    @Test
    fun `the drop pass leaves the playing track's addon copy alone`() {
        assertEquals(emptyList<String>(), dropPass(keep = setOf("id1"), keepsAddonCopy = false))
        assertEquals(emptyList<String>(), dropPass(keep = emptySet(), keepsAddonCopy = true))
    }

    @Test
    fun `the drop pass removes a partial addon copy nobody keeps`() {
        assertEquals(listOf("id1"), dropPass(keep = emptySet(), keepsAddonCopy = false))
    }

    /** A cache of one played JioSaavn substitute entry "vid#alt": never a purge candidate by origin. */
    private fun playedSubstituteCache(removed: MutableList<String>): Cache {
        val values = mapOf(
            "bc_origin" to "JIOSAAVN",
            "bc_media_id" to "vid",
            "bc_playback" to "1",
            "bc_played" to "1",
        )
        val meta = Proxy.newProxyInstance(
            ContentMetadata::class.java.classLoader,
            arrayOf(ContentMetadata::class.java),
        ) { _, method, args ->
            when {
                method.name == "get" && method.returnType == String::class.java -> values[args!![0] as String] ?: args[1]
                method.name == "contains" -> (args!![0] as String) in values
                else -> args?.getOrNull(1)
            }
        } as ContentMetadata
        return Proxy.newProxyInstance(
            Cache::class.java.classLoader,
            arrayOf(Cache::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getKeys" -> TreeSet(listOf("vid#alt"))
                "getContentMetadata" -> meta
                "removeResource" -> { removed += args!![0] as String; null }
                else -> null
            }
        } as Cache
    }

    private fun KoCacheRetention.refusedPass(
        cache: Cache,
        keep: Set<String> = emptySet(),
        refused: Boolean = true,
        held: Boolean = false,
    ) = dropForeignEntries(
        cache = cache,
        keep = keep,
        keepAnalysis = false,
        keepsAddonCopy = { false },
        scope = CoroutineScope(Dispatchers.Unconfined),
        onDropped = {},
        isRefusedCopy = { refused },
        playerHoldsKey = { held },
    )

    // Review 11-17 (LEFT-4): the words check refused the row while its #alt copy
    // played, so the drop was skipped; nothing ever removed it afterwards (only
    // OTHER-origin entries are purge candidates) and every later play replayed it.
    @Test
    fun `a refused copy the player held is dropped by a later pass once let go`() {
        val removed = mutableListOf<String>()
        val cache = playedSubstituteCache(removed)
        val retention = KoCacheRetention()
        retention.deferRefusedDrop("vid#alt")

        retention.refusedPass(cache, held = true)
        assertEquals(emptyList<String>(), removed)
        retention.refusedPass(cache, keep = setOf("vid"))
        assertEquals(emptyList<String>(), removed)
        retention.refusedPass(cache)
        assertEquals(listOf("vid#alt"), removed)
        retention.refusedPass(cache)
        assertEquals(listOf("vid#alt"), removed)
    }

    @Test
    fun `a pending copy whose row is no longer refused is left alone`() {
        val removed = mutableListOf<String>()
        val cache = playedSubstituteCache(removed)
        val retention = KoCacheRetention()
        retention.deferRefusedDrop("vid#alt")
        retention.refusedPass(cache, refused = false)
        retention.refusedPass(cache, refused = true)
        assertEquals(emptyList<String>(), removed)
    }

    @Test
    fun `the refused-copy rule keeps what the queue or the player still holds`() {
        assertTrue(refusedCopyShouldDrop(refused = true, keptByQueueOrTiming = false, playerHolds = false))
        assertFalse(refusedCopyShouldDrop(refused = true, keptByQueueOrTiming = true, playerHolds = false))
        assertFalse(refusedCopyShouldDrop(refused = true, keptByQueueOrTiming = false, playerHolds = true))
        assertFalse(refusedCopyShouldDrop(refused = false, keptByQueueOrTiming = false, playerHolds = false))
    }

    // Review 11-20 (AHEAD-1): a complete, verified #hifi FLAC was dropped once the
    // queue moved on, after the song's YouTube copy had been deleted for it.
    @Test
    fun `a complete lossless hifi copy whose clock is known outlives the queue`() {
        val kept = koKeepsAddonCopy("X#hifi", complete = { true }, audio = { true }, keepsUpgradeRendition = { true })
        assertTrue(kept)
        assertFalse(drop(origin = "OTHER", hasPlayback = true, hasPlayed = true, keepsAddonCopy = kept))
    }

    @Test
    fun `a partial, non-audio or unkept hifi copy still drops`() {
        assertFalse(koKeepsAddonCopy("X#hifi", complete = { false }, audio = { true }, keepsUpgradeRendition = { true }))
        assertFalse(koKeepsAddonCopy("X#hifi", complete = { true }, audio = { false }, keepsUpgradeRendition = { true }))
        assertFalse(koKeepsAddonCopy("X#hifi-2", complete = { true }, audio = { true }, keepsUpgradeRendition = { false }))
        // The plain and #alt copies keep v1.9's rule and are never asked the upgrade question.
        assertTrue(koKeepsAddonCopy("X#alt", complete = { true }, audio = { true }, keepsUpgradeRendition = { error("asked") }))
    }

    @Test
    fun `an upgrade rendition is kept only lossless and on the clock still sounding`() {
        fun keeps(lossless: Boolean = true, served: String? = "addon|t1|flac", substitute: Boolean = true, src: String? = "addon", track: String? = "t1") =
            koKeepsUpgradeRendition(lossless, served, substitute, src, track)
        assertTrue(keeps())
        assertFalse("a lossy rendition", keeps(lossless = false))
        assertFalse("no clock after a restart", keeps(substitute = false, src = null, track = null))
        assertFalse("another row sounding", keeps(track = "t2"))
        assertFalse("YouTube sounding", keeps(substitute = false))
        assertFalse("no record of who filled it", keeps(served = null))
    }

    @Test
    fun `the upgrade keep reads the entry and the stream choice`() {
        val values = mapOf("bc_media_id" to "hk1", "bc_hq_lossless" to "1", "bc_served_source" to "addon|t1|flac")
        val meta = Proxy.newProxyInstance(
            ContentMetadata::class.java.classLoader,
            arrayOf(ContentMetadata::class.java),
        ) { _, method, args ->
            when {
                method.name == "get" && method.returnType == String::class.java -> values[args!![0] as String] ?: args[1]
                method.name == "contains" -> (args!![0] as String) in values
                else -> args?.getOrNull(1)
            }
        } as ContentMetadata
        val cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, _ ->
            if (method.name == "getContentMetadata") meta else null
        } as Cache
        val retention = KoCacheRetention()
        com.music.bitchord.playback.StreamChoice.forget("hk1")
        assertFalse("nothing in memory names it", retention.keepsUpgradeRendition(cache, "hk1#hifi"))
        com.music.bitchord.playback.StreamChoice.remember(
            "hk1",
            com.music.bitchord.data.sources.SourceStream("https://a.example/f", sourceConfigId = "addon", trackId = "t1"),
            substituted = true,
        )
        try {
            assertTrue(retention.keepsUpgradeRendition(cache, "hk1#hifi"))
        } finally {
            com.music.bitchord.playback.StreamChoice.forget("hk1")
        }
    }

    @Test
    fun `the cache's addon keep no longer refuses every hifi key`() {
        val root = java.io.File(".").canonicalFile
        val cache = listOf("app/", "").map { java.io.File(root, "${it}src/main/java/com/music/bitchord/playback/AudioCache.kt") }
            .first { it.exists() }.readText()
        val keeps = cache.substringAfter("private fun keepsAddonCopy(").substringBefore("private val koRetention")
        assertFalse(keeps.contains("!key.contains(\"#hifi\") &&"))
        assertTrue(keeps.contains("koKeepsAddonCopy(key, ::isComplete, { containerOf(it) != null })"))
        assertTrue(keeps.contains("koRetention.keepsUpgradeRendition(cache, it)"))
        assertTrue(keeps.contains("!isRefusedCopy(key)"))
    }

    @Test
    fun `ordinary played entries are never candidates`() {
        assertFalse(drop(origin = "YOUTUBE"))
        assertFalse(drop(origin = "YOUTUBE", hasPlayback = true, hasPlayed = true))
        assertFalse(drop(origin = null))
    }
}
