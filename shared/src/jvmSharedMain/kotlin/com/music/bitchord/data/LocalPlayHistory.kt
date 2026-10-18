package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException

/**
 * One play as this device recorded it, with the cover the listener actually saw.
 *
 * [playedAtMs] is what orders the local shelf. The two applications keep their
 * own listening records (Android's `ListeningStats`, the desktop's
 * `DesktopListeningStats`), so this is the shared shape they hand to the
 * youtube.com fallback.
 */
data class LocalPlay(
    val song: Song,
    val playedAtMs: Long,
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
    }

    private const val TAG = "BitChord"

    @Volatile
    private var source: Source? = null

    fun install(source: Source?) {
        this.source = source
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

    internal fun resetForTest() {
        source = null
    }
}
