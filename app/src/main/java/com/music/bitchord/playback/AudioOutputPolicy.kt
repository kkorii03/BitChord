package com.music.bitchord.playback

import com.music.bitchord.data.settings.OutputPcmMode

/**
 * Decides whether Media3 may open a PCM-float AudioTrack.
 *
 * Float output is not a quality switch that is safe on every Android route.
 * Some OEM speaker paths accept the AudioTrack and then convert it to PCM16 in
 * AudioFlinger; on affected Samsung FLAC decoders that combination can also
 * produce timestamp discontinuities and severely distorted output. Only an
 * explicitly selected external route that advertises PCM float is allowed.
 */
internal object AudioOutputPolicy {
    /** Backwards-compatible USB-specific check. */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        isPreferredUsbRoute: Boolean,
        advertisesPcmFloat: Boolean,
    ): Boolean = requestedMode == OutputPcmMode.FLOAT_32 &&
        isPreferredUsbRoute &&
        advertisesPcmFloat

    /**
     * Route-aware float output decision.
     *
     * - Built-in phone speaker (PHONE) must remain capped at 16-bit to prevent OEM mixer
     *   issues and distortion on affected device paths.
     * - External routes (USB, Bluetooth, Wired, HDMI) are allowed to use Float32 output
     *   if requested and actively advertised by the route's AudioDeviceInfo encodings.
     */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        routeKind: AudioRouting.Kind,
        advertisesPcmFloat: Boolean,
    ): Boolean {
        if (requestedMode != OutputPcmMode.FLOAT_32) return false
        if (routeKind == AudioRouting.Kind.PHONE) return false
        return advertisesPcmFloat
    }

    /** Samsung's vendor FLAC decoder emits invalid timestamps with PCM float. */
    fun isUnsafeFloatFlacDecoder(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "c2.sec.flac.decoder" ||
            (normalized.startsWith("omx.sec.") && normalized.contains("flac"))
    }

    /**
     * The rates the platform mixer and Bluetooth codecs are expected to accept
     * without opening a silent track.
     *
     * 88.2 kHz is deliberately *not* here even though it is a valid LDAC rate.
     * On the reported device a PCM16 AudioTrack opened at 88200 Hz on the
     * Bluetooth route produced no sound, while the same track at 48 kHz did.
     */
    private val SAFE_MIXER_RATES = setOf(44_100, 48_000)

    /**
     * The rate to resample [sourceRateHz] to before opening AudioTrack, or null
     * to keep the source rate.
     *
     * This is the last-resort guard for routes that advertise a rate but then
     * play it silently. It only touches rates above 48 kHz: ordinary CD-rate
     * playback keeps its native rate, and a wired or USB route that explicitly
     * advertises the source rate is left alone.
     *
     * - The 44.1 kHz hi-res family (88.2/176.4/352.8) is folded to 48 kHz on
     *   Bluetooth and the phone mixer even when their descriptors list it.
     *   Those are the rates the reported mixer accepted and then rendered as
     *   silence. A wired or USB route keeps the rate when it lists it.
     * - Any other rate above 48 kHz is kept when the route advertises it;
     *   otherwise it is folded to the first safe rate the route advertises,
     *   or 48 kHz when the route says nothing.
     */
    fun outputSampleRateFor(
        sourceRateHz: Int?,
        routeKind: AudioRouting.Kind,
        advertisedSampleRates: List<Int> = emptyList(),
    ): Int? {
        val source = sourceRateHz?.takeIf { it > 0 } ?: return null
        if (source <= 48_000) return null
        if (source == 88_200 || source == 176_400 || source == 352_800) {
            // A cable or a USB DAC that lists the rate can carry it. Bluetooth
            // and the phone mixer get the safe rate even when their descriptor
            // claims otherwise: that is the failure this guards against.
            val wired = routeKind == AudioRouting.Kind.USB ||
                routeKind == AudioRouting.Kind.WIRED ||
                routeKind == AudioRouting.Kind.HDMI
            return if (wired && source in advertisedSampleRates) null else 48_000
        }
        if (routeKind == AudioRouting.Kind.PHONE) return 48_000
        if (advertisedSampleRates.isEmpty()) return null
        if (source in advertisedSampleRates) return null
        return advertisedSampleRates.firstOrNull { it in SAFE_MIXER_RATES } ?: 48_000
    }
}
