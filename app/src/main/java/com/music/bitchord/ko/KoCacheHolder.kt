package com.music.bitchord.ko

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import com.music.bitchord.playback.DynamicLruCacheEvictor
import java.io.File

/**
 * [KO] cache-open
 *
 * Opens the one SimpleCache lazily. [init] records where it lives; a player
 * built before the startup thread finishes opens it here instead, and both
 * paths meet under one lock so exactly one cache is ever built.
 */
class KoCacheHolder(
    private val context: () -> Context?,
    private val evictor: DynamicLruCacheEvictor,
    private val cacheDirName: String,
    private val legacyDirName: String,
    private val migrate: (File, File, StandaloneDatabaseProvider) -> Unit,
) {
    @Volatile
    var opened: SimpleCache? = null
        private set

    private val openLock = Any()

    fun get(): SimpleCache = opened ?: synchronized(openLock) {
        opened ?: open().also { opened = it }
    }

    private fun open(): SimpleCache {
        val context = checkNotNull(context()) { "AudioCache.init has not run" }
        val dir = File(context.noBackupFilesDir, cacheDirName)
        val databaseProvider = StandaloneDatabaseProvider(context)
        migrate(File(context.cacheDir, legacyDirName), dir, databaseProvider)
        return SimpleCache(dir, evictor, databaseProvider)
    }
}
