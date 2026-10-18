package com.music.bitchord.data.settings

/**
 * The listener's choice for low power, as the settings screen shows it.
 *
 * [AUTO] is the default because the expensive parts of the player — the canvas
 * decoder, the lyric bloom and the background blur — are exactly what a phone
 * under 20% or in the system's saver mode can least afford, and most people
 * never visit either switch on purpose.
 */
enum class LowPowerMode {
    /** Follow the phone: the system's saver, or under 20% and not charging. */
    AUTO,

    /** Always on, whatever the battery or charger says. */
    ON,

    /** Never, whatever the battery or charger says. */
    OFF,
}

/** At or below this level, [LowPowerMode.AUTO] counts as low power when unplugged. */
internal const val LOW_BATTERY_PERCENT = 20

/**
 * Whether the player should take its cheap path.
 *
 * Pure so it can be read by the settings row and tested without a device: the
 * caller supplies what the last power-save and battery broadcasts said.
 *
 * @param powerSave the system's own power saving mode.
 * @param batteryPercent 0..100, as the battery broadcast reports it.
 * @param charging whether the charger is connected (full counts as charging).
 */
internal fun lowPowerActive(
    mode: LowPowerMode,
    powerSave: Boolean,
    batteryPercent: Int,
    charging: Boolean,
): Boolean = when (mode) {
    LowPowerMode.ON -> true
    LowPowerMode.OFF -> false
    LowPowerMode.AUTO -> powerSave ||
        (batteryPercent <= LOW_BATTERY_PERCENT && !charging)
}
