package com.music.bitchord.startup

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.music.bitchord.data.TrackLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether this process currently has a window, as the lifecycle sees it.
 *
 * A foreground service may only be started while the app is on screen, so the
 * download queue and the automatic high-quality pass use this to decide whether
 * to act now or wait for the next [onForeground] event. There is no timer: the
 * listener is the event.
 */
object AppForeground {

    private const val TAG = "BitChord"

    @Volatile
    var isForeground: Boolean = false
        private set

    private val foregroundListeners = CopyOnWriteArrayList<() -> Unit>()
    private val backgroundListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Call once from [Application.onCreate], on the main thread. */
    fun install(app: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isForeground = true
                foregroundListeners.forEach { listener ->
                    runCatching(listener).onFailure {
                        TrackLog.w(TAG, "foreground listener failed: ${it.message}")
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                isForeground = false
                backgroundListeners.forEach { listener ->
                    runCatching(listener).onFailure {
                        TrackLog.w(TAG, "background listener failed: ${it.message}")
                    }
                }
            }
        })
    }

    fun onForeground(listener: () -> Unit) {
        foregroundListeners += listener
    }

    fun onBackground(listener: () -> Unit) {
        backgroundListeners += listener
    }
}
