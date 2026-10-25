package com.music.bitchord.playback

import com.music.bitchord.data.settings.OutputPcmMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputPolicyTest {
    @Test
    fun floatRequiresPreferredUsbRouteAndAdvertisedSupport() {
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                isPreferredUsbRoute = true,
                advertisesPcmFloat = true,
            ),
        )
    }

    @Test
    fun floatFallsBackOnSpeakerEvenWhenRequested() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                isPreferredUsbRoute = false,
                advertisesPcmFloat = true,
            ),
        )
    }

    @Test
    fun floatFallsBackWhenUsbDoesNotAdvertiseIt() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                isPreferredUsbRoute = true,
                advertisesPcmFloat = false,
            ),
        )
    }

    @Test
    fun pcm16NeverRequestsFloat() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.PCM_16,
                isPreferredUsbRoute = true,
                advertisesPcmFloat = true,
            ),
        )
    }

    @Test
    fun routeAwarePhoneIsAlwaysCappedAt16Bit() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.PHONE,
                advertisesPcmFloat = true,
            ),
        )
    }

    @Test
    fun routeAwareUsbAllowsFloatWhenAdvertised() {
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.USB,
                advertisesPcmFloat = true,
            ),
        )
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.USB,
                advertisesPcmFloat = false,
            ),
        )
    }

    @Test
    fun routeAwareBluetoothAllowsFloatOnlyWhenAdvertised() {
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.BLUETOOTH,
                advertisesPcmFloat = true,
            ),
        )
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.BLUETOOTH,
                advertisesPcmFloat = false,
            ),
        )
    }

    @Test
    fun routeAwarePcm16AlwaysReturnsFalse() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.PCM_16,
                routeKind = AudioRouting.Kind.BLUETOOTH,
                advertisesPcmFloat = true,
            ),
        )
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.PCM_16,
                routeKind = AudioRouting.Kind.USB,
                advertisesPcmFloat = true,
            ),
        )
    }

    @Test
    fun samsungVendorFlacDecoderIsBlockedForFloatOutput() {
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("c2.sec.flac.decoder"))
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("OMX.SEC.FLAC.Decoder"))
        assertFalse(AudioOutputPolicy.isUnsafeFloatFlacDecoder("c2.android.flac.decoder"))
    }

    @Test
    fun cdAnd48kRatesAreLeftAlone() {
        assertNull(
            AudioOutputPolicy.outputSampleRateFor(
                44_100,
                AudioRouting.Kind.BLUETOOTH,
                listOf(44_100, 48_000),
            ),
        )
        assertNull(
            AudioOutputPolicy.outputSampleRateFor(
                48_000,
                AudioRouting.Kind.BLUETOOTH,
                listOf(44_100, 48_000),
            ),
        )
    }

    @Test
    fun bluetoothFoldsThe88kFamilyTo48k() {
        assertEquals(
            48_000,
            AudioOutputPolicy.outputSampleRateFor(
                88_200,
                AudioRouting.Kind.BLUETOOTH,
                listOf(44_100, 48_000, 88_200, 96_000),
            ) ?: -1,
        )
        assertEquals(
            48_000,
            AudioOutputPolicy.outputSampleRateFor(
                176_400,
                AudioRouting.Kind.PHONE,
                emptyList(),
            ) ?: -1,
        )
    }

    @Test
    fun directUsbKeepsTheNativeHiResRate() {
        assertNull(
            AudioOutputPolicy.outputSampleRateFor(
                88_200,
                AudioRouting.Kind.USB,
                listOf(44_100, 48_000, 88_200),
            ),
        )
        assertNull(
            AudioOutputPolicy.outputSampleRateFor(
                88_200,
                AudioRouting.Kind.WIRED,
                listOf(44_100, 48_000, 88_200),
            ),
        )
    }

    @Test
    fun advertised96kOnBluetoothIsKept() {
        assertNull(
            AudioOutputPolicy.outputSampleRateFor(
                96_000,
                AudioRouting.Kind.BLUETOOTH,
                listOf(48_000, 96_000),
            ),
        )
    }

    @Test
    fun unadvertisedHiResRateFallsBackToASafeMixerRate() {
        assertEquals(
            48_000,
            AudioOutputPolicy.outputSampleRateFor(
                96_000,
                AudioRouting.Kind.BLUETOOTH,
                listOf(44_100, 48_000),
            ) ?: -1,
        )
        assertEquals(
            44_100,
            AudioOutputPolicy.outputSampleRateFor(
                192_000,
                AudioRouting.Kind.WIRED,
                listOf(44_100, 48_000),
            ) ?: -1,
        )
    }

    @Test
    fun unknownOrInvalidRateIsNotResampled() {
        assertNull(
            AudioOutputPolicy.outputSampleRateFor(
                null,
                AudioRouting.Kind.BLUETOOTH,
                listOf(44_100, 48_000),
            ),
        )
        assertNull(
            AudioOutputPolicy.outputSampleRateFor(
                0,
                AudioRouting.Kind.BLUETOOTH,
                listOf(44_100, 48_000),
            ),
        )
    }
}
