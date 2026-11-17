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

    // C5b-1: the fold is read only when a sink configures, so a route change
    // under an open 44.1k hi-res track must rebuild the players.
    @Test
    fun routeChangeRebuildsWhenTheHiResFoldChanges() {
        val usb = AudioRouting.Kind.USB
        val bt = AudioRouting.Kind.BLUETOOTH
        val phone = AudioRouting.Kind.PHONE
        assertTrue(AudioOutputPolicy.routeChangeNeedsRateRebuild(88_200, usb, listOf(88_200), bt, emptyList()))
        assertTrue(AudioOutputPolicy.routeChangeNeedsRateRebuild(176_400, usb, listOf(176_400), phone, emptyList()))
        assertTrue(AudioOutputPolicy.routeChangeNeedsRateRebuild(88_200, bt, emptyList(), usb, listOf(88_200)))
        assertFalse(AudioOutputPolicy.routeChangeNeedsRateRebuild(48_000, usb, listOf(48_000), bt, emptyList()))
        assertFalse(AudioOutputPolicy.routeChangeNeedsRateRebuild(88_200, bt, emptyList(), phone, emptyList()))
        assertFalse(AudioOutputPolicy.routeChangeNeedsRateRebuild(null, usb, listOf(88_200), bt, emptyList()))
        assertFalse(AudioOutputPolicy.routeChangeNeedsRateRebuild(88_200, usb, listOf(88_200), usb, listOf(88_200)))
    }

    // NR-3: AudioDeviceInfo.getSampleRates() empty means "arbitrary rates".
    @Test
    fun aDacThatReportsNoRateListKeepsTheHiResRate() {
        assertNull(AudioOutputPolicy.outputSampleRateFor(88_200, AudioRouting.Kind.USB, emptyList()))
        assertNull(AudioOutputPolicy.outputSampleRateFor(176_400, AudioRouting.Kind.WIRED, emptyList()))
        // Bluetooth and the phone mixer still fold, whatever they report.
        assertEquals(48_000, AudioOutputPolicy.outputSampleRateFor(88_200, AudioRouting.Kind.BLUETOOTH, emptyList()) ?: -1)
        assertEquals(48_000, AudioOutputPolicy.outputSampleRateFor(88_200, AudioRouting.Kind.PHONE, emptyList()) ?: -1)
        // A list that names other rates only is still "not listed".
        assertEquals(48_000, AudioOutputPolicy.outputSampleRateFor(88_200, AudioRouting.Kind.USB, listOf(44_100, 48_000)) ?: -1)
        // Moving such a DAC's 88.2 kHz track to Bluetooth now has to rebuild.
        assertTrue(
            AudioOutputPolicy.routeChangeNeedsRateRebuild(
                88_200, AudioRouting.Kind.USB, emptyList(), AudioRouting.Kind.BLUETOOTH, emptyList(),
            ),
        )
    }
}
