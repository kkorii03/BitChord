package com.music.bitchord.ko

import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ListeningStatsSeed
import com.music.bitchord.data.stats.SeedPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [KO] listening-seed
 *
 * The debug tour's historical plays. Event-driven like ordinary recording:
 * each play goes through the same [ListeningStats.record] path, then the
 * touched months are joined before returning.
 */
internal object KoListeningStatsSeed {

    /**
     * Records [plays] at the times they happened, then waits for every touched
     * month to reach disk.
     *
     * The debug tour force-stops the app between seeding and the launch whose
     * screenshots matter, so a write still queued behind the force-stop would
     * be a Home with no Recents on the second launch.
     */
    suspend fun seedHistorical(plays: List<SeedPlay>): Int = withContext(Dispatchers.IO) {
        if (!ListeningStats.koReady() || plays.isEmpty()) return@withContext 0
        // Oldest first, so each month is opened once and retired in order.
        // The whole run holds the bucket lock: the startup warm-up opens the
        // current month on its own coroutine, and letting it interleave would
        // retire a half-written older month.
        synchronized(ListeningStats.koLock()) {
            plays.sortedBy { it.playedAtMs }.forEach { play ->
                ListeningStats.record(play.song, play.playedMs, play.countsAsPlay, play.playedAtMs)
            }
        }
        ListeningStats.koFlushAndAwait()
        ListeningStats.koAwaitWriter()
        plays.size
    }

    /** Seeds the tour's history only when this device has not listened yet. */
    suspend fun seedIfEmpty(nowMs: Long = System.currentTimeMillis()): Int {
        if (!ListeningStats.koReady()) return 0
        if (ListeningStats.recentTracks(1).isNotEmpty()) return 0
        return seedHistorical(ListeningStatsSeed.plan(nowMs))
    }
}
