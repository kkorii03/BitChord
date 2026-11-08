package com.music.bitchord.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phone 11-08: the cache-entry settle walked YouTube only after the addon
 * lookup had given up (4 s), so a replayed track took 6.3 s to sound. The old
 * order is `lookup(); resolve()`, under which the first test hangs: the walk
 * is never started while the lookup is still waiting.
 */
class FirstSoundRaceTest {

    @Test
    fun theYouTubeWalkStartsWhileTheAddonLookupIsStillWaiting() = runBlocking {
        val walkStarted = CompletableDeferred<Unit>()
        val lookupMayEnd = CompletableDeferred<Unit>()
        val settle = withTimeout(5_000) {
            FirstSoundRace.settle<String>(
                scope = this,
                racesYouTube = false,
                youTube = { walkStarted.complete(Unit); "https://yt/url" },
                // The lookup ends only once the walk has begun: serial code
                // would wait here for ever and the timeout fails the test.
                lookup = { walkStarted.await(); lookupMayEnd.complete(Unit); null },
                race = { error("a row nobody is waiting on does not race") },
            )
        }
        assertTrue(lookupMayEnd.isCompleted)
        assertNull(settle.addon)
        assertEquals("https://yt/url", settle.youTube.await().getOrThrow())
    }

    @Test
    fun theWaitedLookupStillWinsWithAnAddon() = runBlocking {
        val settle = FirstSoundRace.settle(
            scope = this,
            racesYouTube = false,
            youTube = { "https://yt/url" },
            lookup = { "flac" },
            race = { error("not raced") },
        )
        assertEquals("flac", settle.addon)
    }

    @Test
    fun aPlayingRowTakesYouTubeWhenItAnswersFirst() = runBlocking(Dispatchers.Default) {
        val addonNever = CompletableDeferred<String?>()
        val settle = withTimeout(5_000) {
            FirstSoundRace.settle(
                scope = this,
                racesYouTube = true,
                youTube = { "https://yt/url" },
                lookup = { error("a playing row races") },
                race = { walk ->
                    select<String?> {
                        addonNever.onAwait { it }
                        walk.onAwait { null }
                    }
                },
            )
        }
        assertNull(settle.addon)
        assertEquals("https://yt/url", settle.youTube.await().getOrThrow())
    }

    @Test
    fun aRefusedSettleStopsTheWalk() = runBlocking {
        val walkMayEnd = CompletableDeferred<Unit>()
        var walk: kotlinx.coroutines.Deferred<Result<String>>? = null
        val failure = runCatching {
            FirstSoundRace.settle<String>(
                scope = this,
                racesYouTube = true,
                youTube = { walkMayEnd.await(); "late" },
                lookup = { null },
                race = { w -> walk = w; throw IllegalStateException("lookup broke") },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(walk!!.isCancelled)
    }
}
