package com.music.bitchord.playback

import com.music.bitchord.data.settings.OutputPcmMode

/**
 * Decides whether Media3 may open a PCM-float AudioTrack.
 *
 * Two rules apply to an ordinary (non-direct) mix-path AudioTrack:
 *
 * - External routes (Bluetooth, wired, USB, HDMI) take float whenever the
 *   listener asked for it, regardless of what the route's `AudioDeviceInfo`
 *   encoding list advertises. AudioFlinger's mixer accepts float input on
 *   every mix path, so the advertised list describes what the codec/device
 *   profile can be handed directly, not what the mixer can accept. Reading
 *   it as a cap truncated 24-bit sources (e.g. LDAC) to 16 bits before the
 *   codec ever saw them. If such a device genuinely refuses the float track,
 *   [com.music.bitchord.playback.audio.PrecisionAudioSink] reconfigures the
 *   same stream as PCM16.
 *
 * - The built-in phone speaker is always PCM16. Some OEM speaker paths
 *   accept a float AudioTrack and only then convert it to PCM16 inside
 *   AudioFlinger; on the affected Samsung FLAC decoder that produces
 *   timestamp discontinuities and severe distortion. Because the track is
 *   accepted rather than rejected, the sink's rejection fallback cannot
 *   catch it, so the speaker must never be opened as float in the first
 *   place.
 *
 * Direct and offloaded paths are chosen by
 * [com.music.bitchord.playback.audio.OutputNegotiator] before this applies.
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
     * - Built-in phone speaker (PHONE) must remain capped at 16-bit to prevent OEM mixer
     *   issues and distortion on affected device paths.
     * - External routes (USB, Bluetooth, Wired, HDMI) are allowed to use Float32 output
     *   if requested and actively advertised by the route's AudioDeviceInfo encodings.
     * - A Bluetooth route whose negotiated A2DP codec carries more than 16 bits
     *   (LDAC / aptX HD at 24 or 32 bits) uses float regardless of the requested mode.
     *   A2DP devices almost never list PCM_FLOAT in their encodings, and without a
     *   float track Media3 truncates a hi-res source to 16 bits before the codec
     *   ever sees it. [bluetoothCodecBitDepth] must come from authoritative telemetry.
     */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        routeKind: AudioRouting.Kind,
        advertisesPcmFloat: Boolean,
        bluetoothCodecBitDepth: Int? = null,
    ): Boolean {
        if (routeKind == AudioRouting.Kind.PHONE) return false
        if (routeKind == AudioRouting.Kind.BLUETOOTH && (bluetoothCodecBitDepth ?: 0) > 16) return true
        if (requestedMode != OutputPcmMode.FLOAT_32) return false
        return advertisesPcmFloat
    }

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
