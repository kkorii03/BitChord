package com.music.bitchord.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.ACTION_TOGGLE_AUTOPLAY
import com.music.bitchord.playback.ACTION_TOGGLE_SHUFFLE
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.toMediaItem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DebugScenarioReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCENARIO) return
        val pending = goAsync()
        Thread {
            try {
                handle(context, intent)
            } catch (error: Throwable) {
                TrackLog.w(TAG, "debug-scenario: failed=${error.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        if (command.isBlank()) {
            TrackLog.w(TAG, "debug-scenario: missing command")
            return
        }
        val appContext = context.applicationContext
        val token = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java),
        )
        val future = MediaController.Builder(appContext, token).buildAsync()
        val controller = future.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        try {
            when (command) {
                COMMAND_REPEAT -> {
                    val mode = intent.getIntExtra(EXTRA_MODE, Player.REPEAT_MODE_OFF)
                    onMain {
                        controller.setRepeatMode(mode)
                        TrackLog.i(TAG, "debug-scenario: repeat=$mode")
                    }
                }
                COMMAND_SHUFFLE -> {
                    val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                    onMain {
                        if (QueueShuffle.enabled.value != enabled) {
                            send(controller, ACTION_TOGGLE_SHUFFLE)
                        }
                        TrackLog.i(
                            TAG,
                            "debug-scenario: shuffle=$enabled was=${QueueShuffle.enabled.value}",
                        )
                    }
                }
                COMMAND_AUTOPLAY -> {
                    val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                    onMain {
                        if (AppSettings.autoplay.value != enabled) {
                            send(controller, ACTION_TOGGLE_AUTOPLAY)
                        }
                        TrackLog.i(
                            TAG,
                            "debug-scenario: autoplay=$enabled was=${AppSettings.autoplay.value}",
                        )
                    }
                }
                COMMAND_CROSSFADE -> {
                    val seconds = intent.getIntExtra(EXTRA_SECONDS, 8)
                    AppSettings.setCrossfadeSeconds(seconds)
                    TrackLog.i(TAG, "debug-scenario: crossfade=${seconds}s")
                }
                COMMAND_PLAY_LOCAL -> playLocal(appContext, intent, controller)
                else -> TrackLog.w(TAG, "debug-scenario: unknown command=$command")
            }
        } finally {
            runCatching { onMain { controller.release() } }
        }
    }

    /**
     * MediaController is created on the main looper and rejects every call
     * from another thread. The receiver runs on a worker so MediaStore and
     * file IO do not block the UI, but only those stay there; every player
     * command hops back here.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "main looper timeout"
        }
        failure.get()?.let { throw it }
    }

    private fun send(controller: MediaController, action: String) {
        controller.sendCustomCommand(
            SessionCommand(action, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY,
        ).get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun playLocal(context: Context, intent: Intent, controller: MediaController) {
        val paths = pathsOf(intent.getStringExtra(EXTRA_PATHS))
        val library = runBlocking { LocalMediaRepository.getLocalMusic(context) }
        // MediaStore rows carry the content:// URI the app is allowed to read;
        // matching the pushed files against them keeps the queue deterministic
        // without handing ExoPlayer a file:// path it cannot open.
        val songs = if (paths.isEmpty()) {
            library
        } else {
            val byName = library.associateBy { it.localPath?.let(::File)?.name }
            paths.mapNotNull { path ->
                byName[File(path).name] ?: songFromPath(path)
            }.ifEmpty { library }
        }
        if (songs.isEmpty()) {
            TrackLog.w(TAG, "debug-scenario: play-local empty")
            return
        }
        onMain {
            if (QueueShuffle.enabled.value) send(controller, ACTION_TOGGLE_SHUFFLE)
            controller.setRepeatMode(Player.REPEAT_MODE_OFF)
            controller.setMediaItems(songs.map { it.toMediaItem() }, 0, 0L)
            controller.prepare()
            controller.play()
            TrackLog.i(
                TAG,
                "debug-scenario: play-local n=${songs.size} first=${songs.first().title} " +
                    "id=${songs.first().videoId}",
            )
        }
    }

    private fun pathsOf(paths: String?): List<String> =
        paths.orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun songFromPath(path: String): Song? {
        val file = File(path)
        if (!file.isFile) return null
        val uri = Uri.fromFile(file).toString()
        return Song(
            videoId = uri,
            title = file.nameWithoutExtension,
            artist = "Smoke",
            thumbnailUrl = null,
            localUri = uri,
            localPath = path,
        )
    }

    companion object {
        const val ACTION_SCENARIO = "com.music.bitchord.debug.SCENARIO"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_MODE = "mode"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_PATHS = "paths"

        private const val COMMAND_REPEAT = "repeat"
        private const val COMMAND_SHUFFLE = "shuffle"
        private const val COMMAND_AUTOPLAY = "autoplay"
        private const val COMMAND_CROSSFADE = "crossfade"
        private const val COMMAND_PLAY_LOCAL = "play-local"

        private const val TAG = "BitChord"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val COMMAND_TIMEOUT_SECONDS = 10L
    }
}
