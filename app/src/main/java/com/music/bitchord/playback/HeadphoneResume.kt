package com.music.bitchord.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.music.bitchord.data.DebugLog as Log

/**
 * Tells the playback service when headphones arrive — Bluetooth or wired —
 * so a paused song can carry on where it left off. See
 * [com.music.bitchord.data.settings.AppSettings.resumeOnHeadphones].
 *
 * Android reports the devices already connected the moment the callback is
 * registered; those are not arrivals, so anything reported in the first
 * moments after [attach] is ignored. One pair of earbuds also arrives as more
 * than one device (A2DP, then the headset profile), so arrivals close together
 * are folded into one, and the call waits for the route to settle onto the
 * headphones before anything is played — never out of the phone's speaker.
 */
internal class HeadphoneResume(
    private val context: Context,
    private val onConnected: () -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var attachedAt = 0L
    private val fire = Runnable { onConnected() }

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (System.currentTimeMillis() - attachedAt < STARTUP_QUIET_MS) return
            if (addedDevices.none { it.isSink && it.type in HEADPHONE_TYPES }) return
            Log.d(TAG, "headphones connected")
            main.removeCallbacks(fire)
            main.postDelayed(fire, SETTLE_MS)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            // Gone again before the route settled: nothing to resume into.
            if (removedDevices.any { it.isSink && it.type in HEADPHONE_TYPES }) main.removeCallbacks(fire)
        }
    }

    fun attach() {
        val manager = audio ?: return
        attachedAt = System.currentTimeMillis()
        runCatching { manager.registerAudioDeviceCallback(callback, main) }
            .onFailure { Log.w(TAG, "headphone resume: could not listen: ${it.message}") }
    }

    fun detach() {
        main.removeCallbacks(fire)
        runCatching { audio?.unregisterAudioDeviceCallback(callback) }
    }

    /** Whether the media is routed to headphones right now. */
    fun onHeadphones(): Boolean {
        val manager = audio ?: return false
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }
    }

    private companion object {
        const val TAG = "BitChord"
        const val STARTUP_QUIET_MS = 2_000L
        const val SETTLE_MS = 1_200L

        val HEADPHONE_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
