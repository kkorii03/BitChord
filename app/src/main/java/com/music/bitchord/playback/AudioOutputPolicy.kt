package com.music.bitchord.playback

import com.music.bitchord.data.settings.OutputPcmMode

/**
 * Decides whether Media3 may open a PCM-float AudioTrack.
 *
 * AudioFlinger's mixer takes float input on every mix path, so a route's
 * `AudioDeviceInfo` encoding list is not a limit on what an ordinary
 * (non-direct) AudioTrack can be opened with. Bluetooth in particular
 * advertises the codec's own PCM16 profile even when the mixer below it is
 * perfectly able to take float; reading that list as a cap truncated 24-bit
 * sources to 16 bits before the codec ever saw them.
 *
 * The only real check is whether the listener asked for float. Direct and
 * offloaded paths are chosen by [com.music.bitchord.playback.audio.OutputNegotiator]
 * before this applies, and if a device genuinely refuses the float track
 * `PrecisionAudioSink` reconfigures the same stream as PCM16. That is also
 * why the speaker is no longer special-cased here: the fallback is the
 * device's answer, not a guess about its OEM mixer.
 */
internal object AudioOutputPolicy {
    /** Backwards-compatible USB-specific check. */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        isPreferredUsbRoute: Boolean,
        advertisesPcmFloat: Boolean,
    ): Boolean = requestedMode == OutputPcmMode.FLOAT_32

    /**
     * Route-aware float output decision.
     *
     * A mix-path AudioTrack (PHONE, BLUETOOTH, WIRED, HDMI) takes float
     * whenever float was requested. The route's advertised encodings are
     * accepted for compatibility but deliberately do not gate the answer —
     * see the class comment. The sink falls back to PCM16 if the device
     * rejects the track.
     */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        routeKind: AudioRouting.Kind,
        advertisesPcmFloat: Boolean,
    ): Boolean = requestedMode == OutputPcmMode.FLOAT_32

    /** Samsung's vendor FLAC decoder emits invalid timestamps with PCM float. */
    fun isUnsafeFloatFlacDecoder(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "c2.sec.flac.decoder" ||
            (normalized.startsWith("omx.sec.") && normalized.contains("flac"))
    }

    /**
     * The rate to resample [sourceRateHz] to before opening AudioTrack, or null
     * to keep the source rate.
     *
     * This is the last-resort guard for routes that advertise a rate but then
     * play it silently. It only touches the 44.1 kHz hi-res family
     * (88.2/176.4/352.8): on the reported device a PCM16 AudioTrack opened at
     * 88200 Hz on the Bluetooth route produced no sound, while the same track
     * at 48 kHz did.
     *
     * Every other rate, including 96/192 kHz, is kept at its native rate and
     * left to the platform mixer. The fallback exists for the one family that
     * was observed to open silently; using it for rates the mixer already
     * carries would needlessly pin the output to PCM16 and insert a converter
     * between the decoder and the hardware.
     *
     * - The 44.1 kHz hi-res family is folded to 48 kHz on Bluetooth and the
     *   phone mixer even when their descriptors list it. A wired or USB route
     *   keeps the rate when it lists it.
     * - Any other rate is kept (null).
     */
    fun outputSampleRateFor(
        sourceRateHz: Int?,
        routeKind: AudioRouting.Kind,
        advertisedSampleRates: List<Int> = emptyList(),
    ): Int? {
        val source = sourceRateHz?.takeIf { it > 0 } ?: return null
        if (source <= 48_000) return null
        if (source != 88_200 && source != 176_400 && source != 352_800) return null
        // A cable or a USB DAC that lists the rate can carry it. Bluetooth
        // and the phone mixer get the safe rate even when their descriptor
        // claims otherwise: that is the failure this guards against.
        val wired = routeKind == AudioRouting.Kind.USB ||
            routeKind == AudioRouting.Kind.WIRED ||
            routeKind == AudioRouting.Kind.HDMI
        return if (wired && source in advertisedSampleRates) null else 48_000
    }
}
