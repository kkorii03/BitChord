package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One play as this device recorded it, with the cover the listener actually saw.
 *
 * [playedAtMs] is what orders the local shelf. The two applications keep their
 * own listening records (Android's `ListeningStats`, the desktop's
 * `DesktopListeningStats`), so this is the shared shape they hand to the
 * youtube.com fallback.
 *
 * [plays] is the lifetime play count for the row in a rediscovery query, and
 * defaults to one for an ordinary recent play.
 */
data class LocalPlay(
    val song: Song,
    val playedAtMs: Long,
    val plays: Int = 1,
)

/**
 * One artist this device has listened to enough for the Listen again row.
 *
 * [artistId] is only filled from a record that already stored the channel;
 * otherwise the fallback resolves the name once and caches it.
 */
data class LocalArtist(
    val name: String,
    val artistId: String? = null,
    val plays: Int = 0,
    val thumbnailUrl: String? = null,
)

/**
 * The device's own play history, as installed by the application around the
 * shared data layer.
 *
 * The fallback lives in `shared`, so it cannot read either app's stats store
 * directly. Installing a source at startup keeps the dependency pointing the
 * right way and leaves the shared tests with an empty, harmless default.
 */
object LocalPlayHistory {

    fun interface Source {
        /** At most [limit] plays, newest first, already distinct by video id. */
        suspend fun recent(limit: Int): List<LocalPlay>

        /**
         * Artists with at least [minPlays] whole plays in the last [days] days,
         * most played first. Empty when the store does not answer.
         */
        suspend fun topArtists(days: Int, minPlays: Int): List<LocalArtist> = emptyList()

        /**
         * Songs whose lifetime play count is high and whose last play is older
         * than [olderThanDays] days, oldest last play first.
         */
        suspend fun rediscover(limit: Int, olderThanDays: Int): List<LocalPlay> = emptyList()

        /**
         * Emits once each time a play is written to [recent], so the Home
         * Recents shelf can follow the device's own history without polling.
         */
        fun changes(): Flow<Unit> = emptyFlow()
    }

    /** The signed-in session's display name, for the Listen again strapline. */
    fun interface AccountNameSource {
        fun displayName(): String?
    }

    private const val TAG = "BitChord"

    @Volatile
    private var source: Source? = null

    @Volatile
    private var accountNameSource: AccountNameSource? = null

    fun install(source: Source?) {
        this.source = source
    }

    fun installAccountName(source: AccountNameSource?) {
        accountNameSource = source
    }

    /**
     * The most recent local plays, or an empty list when no source is installed
     * or the store cannot answer. A broken local record must not take the
     * youtube.com fallback down with it.
     */
    suspend fun recent(limit: Int): List<LocalPlay> {
        if (limit <= 0) return emptyList()
        val current = source ?: return emptyList()
        return try {
            current.recent(limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Local play history unavailable: ${failure.message}")
            emptyList()
        }
    }

    /** @see Source.topArtists */
    suspend fun topArtists(days: Int, minPlays: Int): List<LocalArtist> {
        if (days <= 0 || minPlays <= 0) return emptyList()
        val current = source ?: return emptyList()
        return try {
            current.topArtists(days, minPlays)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Local artist history unavailable: ${failure.message}")
            emptyList()
        }
    }

    /** @see Source.rediscover */
    suspend fun rediscover(limit: Int, olderThanDays: Int): List<LocalPlay> {
        if (limit <= 0) return emptyList()
        val current = source ?: return emptyList()
        return try {
            current.rediscover(limit, olderThanDays)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Local rediscovery unavailable: ${failure.message}")
            emptyList()
        }
    }

    /**
     * Play events from the installed source, or a flow that never completes and
     * never emits when there is none. Collecting is event-driven: the source
     * emits when it records a play, not on a timer.
     */
    fun changes(): Flow<Unit> = source?.changes() ?: emptyFlow()

    /** The signed-in account's name, or null when no session source is installed. */
    fun accountName(): String? = try {
        accountNameSource?.displayName()?.takeIf { it.isNotBlank() }
    } catch (failure: Throwable) {
        Log.w(TAG, "Account name unavailable: ${failure.message}")
        null
    }

    internal fun resetForTest() {
        source = null
        accountNameSource = null
    }
}
