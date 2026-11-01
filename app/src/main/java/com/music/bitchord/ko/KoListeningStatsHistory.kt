package com.music.bitchord.ko

import android.content.Context
import com.music.bitchord.data.stats.StoredBucket
import kotlinx.serialization.json.Json
import java.io.File

/**
 * [KO] listening-history
 *
 * Whether this install has recorded any listening yet. Read before the store
 * opens, so the onboarding decision does not wait for playback.
 */
internal object KoListeningStatsHistory {

    private const val DIRECTORY = "listening"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /**
     * Kept callable before ListeningStats.init because the settings read runs
     * before the store opens, and an onboarding flag that predates the welcome
     * pages is decided there. An empty month still carries `"tracks":[]`;
     * anything with a play, an artist or time in the day buckets counts as
     * history.
     */
    fun hasStoredHistory(context: Context): Boolean {
        val files = File(context.filesDir, DIRECTORY).listFiles() ?: return false
        return files.any { file ->
            if (!file.isFile || !file.name.endsWith(".json")) return@any false
            val bucket = runCatching {
                json.decodeFromString(StoredBucket.serializer(), file.readText())
            }.getOrNull() ?: return@any false
            bucket.tracks.isNotEmpty() ||
                bucket.artists.isNotEmpty() ||
                bucket.albums.isNotEmpty() ||
                bucket.hours.any { it > 0 } ||
                bucket.days.isNotEmpty()
        }
    }
}
