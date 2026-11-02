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
    fun floatIsAllowedOffUsbWhenRequested() {
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                isPreferredUsbRoute = false,
                advertisesPcmFloat = true,
            ),
        )
    }

    @Test
    fun floatDoesNotDependOnAdvertisedEncodings() {
        assertTrue(
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
    fun routeAwarePhoneAlwaysUsesPcm16EvenWhenFloatRequested() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.PHONE,
                advertisesPcmFloat = true,
            ),
        )
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.PHONE,
                advertisesPcmFloat = false,
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
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.USB,
                advertisesPcmFloat = false,
            ),
        )
    }

    @Test
    fun routeAwareBluetoothTakesFloatEvenWhenOnlyPcm16IsAdvertised() {
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.BLUETOOTH,
                advertisesPcmFloat = true,
            ),
        )
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.BLUETOOTH,
                advertisesPcmFloat = false,
            ),
        )
    }

    @Test
    fun bluetoothHiResCodecEnablesFloatRegardlessOfRequestedMode() {
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.PCM_16,
                routeKind = AudioRouting.Kind.BLUETOOTH,
                advertisesPcmFloat = false,
                bluetoothCodecBitDepth = 24,
            ),
        )
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.PCM_16,
                routeKind = AudioRouting.Kind.BLUETOOTH,
                advertisesPcmFloat = false,
                bluetoothCodecBitDepth = 16,
            ),
        )
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.PHONE,
                advertisesPcmFloat = true,
                bluetoothCodecBitDepth = 24,
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
    fun otherHiResRatesAreLeftToTheOsMixer() {
        assertNull(
            AudioOutputPolicy.outputSampleRateFor(
                96_000,
                AudioRouting.Kind.BLUETOOTH,
                listOf(44_100, 48_000),
            ),
        )
        assertNull(
            AudioOutputPolicy.outputSampleRateFor(
                192_000,
                AudioRouting.Kind.WIRED,
                listOf(44_100, 48_000),
            ),
        )
        assertNull(
            AudioOutputPolicy.outputSampleRateFor(
                96_000,
                AudioRouting.Kind.PHONE,
                emptyList(),
            ),
        )
    }

    @Test
    fun bluetoothFoldsThe352kRateTo48k() {
        assertEquals(
            48_000,
            AudioOutputPolicy.outputSampleRateFor(
                352_800,
                AudioRouting.Kind.BLUETOOTH,
                listOf(44_100, 48_000, 352_800),
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
