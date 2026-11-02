package com.music.bitchord.ko

import android.content.Intent
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug-only adb hook for the emulator smoke test: `--es bitchord_queue
 * id1,id2,...` replaces the queue with those ids, in order, and starts the
 * first. Being a real queue is the point — the next-song preparation then works
 * on the next test song instead of on YouTube radio. Release builds ignore it.
 */
internal object KoDebugQueue {

    private const val EXTRA_IDS = "bitchord_queue"
    private const val EXTRA_CONSUMED = "bitchord.debug.queueConsumed"

    private val _pending = MutableStateFlow<List<String>?>(null)

    /** The requested queue, or null. Cleared by [handled] once it started. */
    val pending: StateFlow<List<String>?> = _pending.asStateFlow()

    fun consume(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG || intent == null) return false
        if (intent.getBooleanExtra(EXTRA_CONSUMED, false)) return false
        val ids = parseDebugQueueIds(intent.getStringExtra(EXTRA_IDS)) ?: return false
        intent.putExtra(EXTRA_CONSUMED, true)
        _pending.value = ids
        return true
    }

    fun handled() {
        _pending.value = null
    }
}

/** Splits a comma-separated id list, dropping blanks; null when nothing is left. */
internal fun parseDebugQueueIds(raw: String?): List<String>? =
    raw.orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .takeIf { it.isNotEmpty() }
