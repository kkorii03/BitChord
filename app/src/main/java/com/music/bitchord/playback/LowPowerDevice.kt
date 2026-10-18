package com.music.bitchord.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.music.bitchord.data.settings.AppSettings

/**
 * Keeps [AppSettings.lowPower] in step with the phone.
 *
 * The system's power-save switch and the battery can change while the app sits
 * in the background with music playing, so this listens rather than reading
 * once at launch. [Intent.ACTION_BATTERY_CHANGED] is sticky: registering
 * delivers the current charge immediately, which is also how the first value
 * arrives without a separate query.
 */
internal object LowPowerDevice {
    private const val TAG = "LowPowerDevice"

    fun start(context: Context) {
        val app = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // Both actions are protected system broadcasts, which reach a
        // non-exported receiver too; Android 13 wants the flag stated, and
        // not exporting it keeps other apps from poking it.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // A power-save change carries no battery figures; the sticky
            // battery broadcast does, and reading it costs one lookup.
            val battery = if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                intent
            } else {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            } ?: return
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            AppSettings.setDevicePower(
                powerSave = power?.isPowerSaveMode == true,
                batteryPercent = percent,
                charging = charging,
            )
        }
    }
}
