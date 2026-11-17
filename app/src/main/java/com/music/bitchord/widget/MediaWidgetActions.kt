package com.music.bitchord.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.music.bitchord.playback.ACTION_TOGGLE_FAVORITE
import com.music.bitchord.playback.ACTION_TOGGLE_SHUFFLE
import com.music.bitchord.playback.PlaybackService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The widget's transport buttons.
 *
 * Playback is reached by **binding** the session, never by starting it. That is
 * the whole reason this is possible at all: `startService` and
 * `startForegroundService` are refused from the background on API 26+ and 31+,
 * and a tap on a home-screen widget is the background — but `bindService` is not
 * restricted, and a `MediaController` binds. Same handshake the app itself uses,
 * from [rememberMediaController][com.music.bitchord.playback.rememberMediaController].
 *
 * Binding also creates [PlaybackService] when needed. Its bounded persisted
 * queue is restored off the main thread and usually lands after the controller
 * has connected, so a command waits for it ([WidgetQueueWaiter]); that is what
 * makes widget playback work after process death.
 */
class MediaWidgetActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in ACTIONS } ?: return
        val app = context.applicationContext

        // Held open across the connect: a controller that arrives after the
        // broadcast has returned arrives in a process that may already be gone.
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()

        val done = AtomicBoolean(false)
        fun release() {
            if (!done.compareAndSet(false, true)) return
            MediaController.releaseFuture(future)
            runCatching { pending.finish() }
        }

        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                if (controller == null) {
                    handler.postDelayed(::release, SETTLE_MS)
                } else {
                    // [KO] A service this tap created restores its queue off the
                    // main thread, so the controller usually connects to an empty
                    // player and the queue lands a moment later as a timeline
                    // change (phone 11-17: every widget tap after a cold start did
                    // nothing). The command waits for that queue instead of being
                    // dropped; a service with nothing saved is left to the
                    // GIVE_UP_MS backstop. Both run on the main thread, so no
                    // change slips in between the check and the listener.
                    lateinit var waiter: WidgetQueueWaiter
                    waiter = WidgetQueueWaiter({ controller.mediaItemCount }) {
                        controller.removeListener(waiter)
                        if (!done.get()) {
                            runCatching { controller.execute(action) }
                            // Not released on the spot. The command has been sent,
                            // but the service still has a stream to resolve before
                            // it goes foreground and can hold itself up; dropping
                            // the last binding inside that window is how a tap on
                            // play ends with a service that shut down instead of
                            // one that started playing.
                            handler.postDelayed(::release, SETTLE_MS)
                        }
                    }
                    if (!waiter.runIfQueued()) controller.addListener(waiter)
                }
            },
            ContextCompat.getMainExecutor(app),
        )
        // If the service never comes up, nothing above ever runs — and a
        // PendingResult that is never finished is a broadcast the system waits out
        // and then reports as not responding.
        handler.postDelayed(::release, GIVE_UP_MS)
    }

    private fun MediaController.execute(action: String) {
        // Only reached with a queue ([WidgetQueueWaiter]); kept as the guard.
        if (mediaItemCount == 0) return
        when (action) {
            ACTION_TOGGLE -> if (playWhenReady) {
                pause()
            } else {
                // Sitting at the end of the queue, play() alone would set
                // playWhenReady on a player with nowhere left to go.
                if (playbackState == Player.STATE_ENDED) seekTo(0L)
                prepareIfIdle()
                play()
            }
            // Skipping does not start anything that wasn't already started —
            // skipping while paused leaves you paused, here as everywhere else.
            // The prepare is so the new track actually loads, and so the session
            // reports it and the widget follows.
            ACTION_NEXT -> {
                seekToNextMediaItem()
                prepareIfIdle()
            }
            ACTION_PREVIOUS -> {
                seekToPreviousMediaItem()
                prepareIfIdle()
            }
            // The same session commands the notification's Favorite and Shuffle
            // send, so the service does the work — optimistic like with a
            // YouTube write behind it, shuffle as one queue transaction — and
            // republishes the widget state when it has.
            ACTION_LIKE -> sendCustomCommand(
                SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY),
                Bundle.EMPTY,
            )
            ACTION_SHUFFLE -> sendCustomCommand(
                SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY),
                Bundle.EMPTY,
            )
        }
    }

    /** An idle player ignores transport commands until it is prepared. */
    private fun MediaController.prepareIfIdle() {
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    companion object {

        const val ACTION_TOGGLE = "com.music.bitchord.widget.TOGGLE"
        const val ACTION_NEXT = "com.music.bitchord.widget.NEXT"
        const val ACTION_PREVIOUS = "com.music.bitchord.widget.PREVIOUS"
        const val ACTION_LIKE = "com.music.bitchord.widget.LIKE"
        const val ACTION_SHUFFLE = "com.music.bitchord.widget.SHUFFLE"

        fun pendingIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                // Distinct per action, so the buttons cannot collapse into
                // one PendingIntent — extras are ignored when they are compared,
                // and only the request code and the action tell them apart.
                REQUEST_BASE + ACTIONS.indexOf(action),
                Intent(context, MediaWidgetActions::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private val ACTIONS =
            listOf(ACTION_TOGGLE, ACTION_NEXT, ACTION_PREVIOUS, ACTION_LIKE, ACTION_SHUFFLE)

        private const val REQUEST_BASE = 100

        /** How long the binding is kept after the command, so the service can settle. */
        private const val SETTLE_MS = 2_000L

        /**
         * Backstop for a session that never connects, or never gets a queue
         * because nothing was saved. Inside the broadcast window.
         */
        private const val GIVE_UP_MS = 7_000L
    }
}

/**
 * Runs a widget command once the player has a queue: at once when it already
 * has one, otherwise on the first timeline change that brings one. At most once.
 */
internal class WidgetQueueWaiter(
    private val queueSize: () -> Int,
    private val command: () -> Unit,
) : Player.Listener {

    private val ran = AtomicBoolean(false)

    /** True when the command has run, now or before; false while there is no queue. */
    fun runIfQueued(): Boolean {
        if (ran.get()) return true
        if (queueSize() == 0) return false
        if (ran.compareAndSet(false, true)) command()
        return true
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        runIfQueued()
    }
}
