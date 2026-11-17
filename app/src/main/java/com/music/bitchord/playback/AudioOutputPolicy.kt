package com.music.bitchord.playback

import com.music.bitchord.data.settings.OutputPcmMode

/**
 * Decides whether Media3 may open a PCM-float AudioTrack.
 *
 * Two rules apply to an ordinary (non-direct) mix-path AudioTrack:
 *
 * - External routes (Bluetooth, wired, USB, HDMI) take float when the
 *   listener asked for it and the route's `AudioDeviceInfo` lists PCM float,
 *   and a Bluetooth route whose authoritative codec telemetry carries more
 *   than 16 bits (LDAC / aptX HD) takes it regardless — see the route-aware
 *   [shouldUseFloatOutput]. That is v1.9's rule. The fork once sent float to
 *   every external route whatever it listed (to stop LDAC sources being cut
 *   to 16 bits); the v1.9 merge replaced it with the codec check, which
 *   covers that case, and the fork's tests that still expected the old rule
 *   were corrected on 11-17 rather than the behaviour changed.
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
     *   keeps the rate when it lists it, or lists nothing (Android's "any rate").
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
        // [KO] NR-3: an empty list is Android's "any rate" (AudioDeviceInfo.getSampleRates),
        // not "none listed"; a DAC that says so keeps 88.2/176.4 kHz.
        return if (wired && (advertisedSampleRates.isEmpty() || source in advertisedSampleRates)) null else 48_000
    }

    /**
     * [KO] C5b-1: whether a route change under an open track changes the
     * [outputSampleRateFor] answer, so the players must be rebuilt. The rule is
     * read only when a sink configures; a moved 88.2 kHz track from a USB DAC
     * to Bluetooth would otherwise keep the rate the fold exists to avoid.
     */
    fun routeChangeNeedsRateRebuild(
        sourceRateHz: Int?,
        oldKind: AudioRouting.Kind,
        oldRates: List<Int>,
        newKind: AudioRouting.Kind,
        newRates: List<Int>,
    ): Boolean = outputSampleRateFor(sourceRateHz, oldKind, oldRates) !=
        outputSampleRateFor(sourceRateHz, newKind, newRates)
}

/**
 * Parks an output rebuild until a crossfade has finished.
 *
 * Replacing two renderers during a blend cuts one half of it, so a route or
 * PCM-mode change that arrives mid-transition must wait. The wait is driven by
 * [CrossfadeController]'s settled event; the old code asked
 * [CrossfadeController.isTransitioning] every 50ms, which is a timer spent on
 * an answer the controller already broadcasts.
 */
internal class OutputReconfigureGate {
    private var parked = false
    // [KO] C5a-1: a parked request remembers whether any of the parked calls
    // asked for a full player rebuild (bit-perfect, USB direct, fixed volume,
    // limited-DAC, 5.1 route). Replaying it as a route-only change dropped it.
    private var parkedRebuild = false

    /** Whether the caller may rebuild now, or the request was parked until [settled]. */
    @Synchronized
    fun request(transitioning: Boolean, rebuild: Boolean = false): Boolean {
        if (transitioning) {
            parked = true
            parkedRebuild = parkedRebuild || rebuild
            return false
        }
        parked = false
        parkedRebuild = false
        return true
    }

    /**
     * The transition finished: null when nothing was parked, otherwise the
     * rebuild flag the parked request must be replayed with.
     */
    @Synchronized
    fun settledRequest(): Boolean? {
        val run = parked
        val rebuild = parkedRebuild
        parked = false
        parkedRebuild = false
        return if (run) rebuild else null
    }

    /** The transition finished; whether a parked rebuild still has to run. */
    @Synchronized
    fun settled(): Boolean = settledRequest() != null
}
