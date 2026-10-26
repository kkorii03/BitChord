package com.music.bitchord.playback

import com.music.bitchord.data.settings.OutputPcmMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputPolicyBoundaryTest {

    private val externalRoutes = listOf(
        AudioRouting.Kind.WIRED,
        AudioRouting.Kind.USB,
        AudioRouting.Kind.BLUETOOTH,
        AudioRouting.Kind.HDMI,
        AudioRouting.Kind.OTHER,
    )

    @Test
    fun everyExternalRouteTakesFloatWhenRequested() {
        for (route in externalRoutes) {
            for (advertises in listOf(true, false)) {
                assertTrue(
                    "route=$route advertises=$advertises",
                    AudioOutputPolicy.shouldUseFloatOutput(
                        OutputPcmMode.FLOAT_32,
                        route,
                        advertises,
                    ),
                )
            }
        }
    }

    @Test
    fun thePhoneSpeakerNeverTakesFloat() {
        for (advertises in listOf(true, false)) {
            assertFalse(
                AudioOutputPolicy.shouldUseFloatOutput(
                    OutputPcmMode.FLOAT_32,
                    AudioRouting.Kind.PHONE,
                    advertises,
                ),
            )
        }
    }

    @Test
    fun pcm16IsPcm16OnEveryRoute() {
        for (route in AudioRouting.Kind.entries) {
            assertFalse(
                "route=$route",
                AudioOutputPolicy.shouldUseFloatOutput(
                    OutputPcmMode.PCM_16,
                    route,
                    advertisesPcmFloat = true,
                ),
            )
        }
    }

    @Test
    fun theLegacyUsbCheckIgnoresItsRouteAndAdvertisingFlags() {
        for (preferredUsb in listOf(true, false)) {
            for (advertises in listOf(true, false)) {
                assertTrue(
                    AudioOutputPolicy.shouldUseFloatOutput(
                        OutputPcmMode.FLOAT_32,
                        isPreferredUsbRoute = preferredUsb,
                        advertisesPcmFloat = advertises,
                    ),
                )
                assertFalse(
                    AudioOutputPolicy.shouldUseFloatOutput(
                        OutputPcmMode.PCM_16,
                        isPreferredUsbRoute = preferredUsb,
                        advertisesPcmFloat = advertises,
                    ),
                )
            }
        }
    }

    @Test
    fun ordinaryRatesUpTo48kAreNeverResampled() {
        for (rate in listOf(8_000, 22_050, 32_000, 44_100, 48_000)) {
            assertNull(
                "rate=$rate",
                AudioOutputPolicy.outputSampleRateFor(
                    rate,
                    AudioRouting.Kind.BLUETOOTH,
                    listOf(44_100, 48_000, rate),
                ),
            )
        }
    }

    @Test
    fun onlyThe441HiResFamilyIsFolded() {
        for (rate in listOf(88_200, 176_400, 352_800)) {
            assertEquals(
                "rate=$rate",
                48_000,
                AudioOutputPolicy.outputSampleRateFor(
                    rate,
                    AudioRouting.Kind.BLUETOOTH,
                    listOf(rate),
                ),
            )
        }
    }

    @Test
    fun ratesJustOutsideTheFamilyAreLeftAlone() {
        for (rate in listOf(88_199, 88_201, 176_399, 176_401, 352_799, 352_801, 48_001)) {
            assertNull(
                "rate=$rate",
                AudioOutputPolicy.outputSampleRateFor(
                    rate,
                    AudioRouting.Kind.BLUETOOTH,
                    listOf(rate),
                ),
            )
        }
    }

    @Test
    fun otherHiResRatesAreLeftToThePlatformMixer() {
        for (rate in listOf(96_000, 192_000, 384_000)) {
            for (route in AudioRouting.Kind.entries) {
                assertNull(
                    "rate=$rate route=$route",
                    AudioOutputPolicy.outputSampleRateFor(rate, route, listOf(rate)),
                )
            }
        }
    }

    @Test
    fun aNullZeroOrNegativeRateIsNotResampled() {
        for (rate in listOf(null, 0, -1, -88_200)) {
            assertNull(
                "rate=$rate",
                AudioOutputPolicy.outputSampleRateFor(rate, AudioRouting.Kind.BLUETOOTH),
            )
        }
    }

    @Test
    fun bluetoothAndThePhoneMixerFoldTheFamilyEvenWhenItIsAdvertised() {
        for (route in listOf(AudioRouting.Kind.BLUETOOTH, AudioRouting.Kind.PHONE)) {
            assertEquals(
                "route=$route",
                48_000,
                AudioOutputPolicy.outputSampleRateFor(
                    88_200,
                    route,
                    listOf(44_100, 48_000, 88_200),
                ),
            )
        }
    }

    @Test
    fun aCableOrDacThatListsTheFamilyRateKeepsIt() {
        for (route in listOf(AudioRouting.Kind.WIRED, AudioRouting.Kind.USB, AudioRouting.Kind.HDMI)) {
            assertNull(
                "route=$route",
                AudioOutputPolicy.outputSampleRateFor(
                    88_200,
                    route,
                    listOf(44_100, 48_000, 88_200),
                ),
            )
            assertNull(
                "route=$route",
                AudioOutputPolicy.outputSampleRateFor(
                    352_800,
                    route,
                    listOf(352_800),
                ),
            )
        }
    }

    @Test
    fun aCableOrDacThatDoesNotListTheFamilyRateGets48k() {
        for (route in listOf(AudioRouting.Kind.WIRED, AudioRouting.Kind.USB, AudioRouting.Kind.HDMI)) {
            assertEquals(
                "route=$route",
                48_000,
                AudioOutputPolicy.outputSampleRateFor(176_400, route, listOf(44_100, 48_000)) ?: -1,
            )
            assertEquals(
                "route=$route",
                48_000,
                AudioOutputPolicy.outputSampleRateFor(176_400, route, emptyList()) ?: -1,
            )
        }
    }

    @Test
    fun otherRoutesNeverKeepTheFamilyRate() {
        for (route in listOf(AudioRouting.Kind.BLUETOOTH, AudioRouting.Kind.PHONE, AudioRouting.Kind.OTHER)) {
            assertEquals(
                "route=$route",
                48_000,
                AudioOutputPolicy.outputSampleRateFor(
                    88_200,
                    route,
                    listOf(44_100, 48_000, 88_200),
                ) ?: -1,
            )
        }
    }

    @Test
    fun aDuplicateAdvertisementChangesNothing() {
        assertEquals(
            48_000,
            AudioOutputPolicy.outputSampleRateFor(
                88_200,
                AudioRouting.Kind.BLUETOOTH,
                listOf(88_200, 88_200, 88_200),
            ) ?: -1,
        )
    }

    @Test
    fun theSamsungFlacDecoderIsRecognisedByItsExactName() {
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("c2.sec.flac.decoder"))
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("C2.SEC.FLAC.DECODER"))
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("C2.Sec.Flac.Decoder"))
    }

    @Test
    fun anySecOmxFlacDecoderIsRecognised() {
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("omx.sec.flac.decoder"))
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("OMX.SEC.FLAC.Decoder"))
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("omx.sec.mp3.flac.decoder"))
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("omx.sec.audio.flac.decoder"))
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("omx.sec.flac"))
    }

    @Test
    fun otherFlacDecodersAreSafe() {
        assertFalse(AudioOutputPolicy.isUnsafeFloatFlacDecoder("omx.google.flac.decoder"))
        assertFalse(AudioOutputPolicy.isUnsafeFloatFlacDecoder("c2.android.flac.decoder"))
        assertFalse(AudioOutputPolicy.isUnsafeFloatFlacDecoder("sec.flac.decoder"))
        assertFalse(AudioOutputPolicy.isUnsafeFloatFlacDecoder("c2.sec.flac.decoder.extra"))
        assertFalse(AudioOutputPolicy.isUnsafeFloatFlacDecoder("omx.sec.truehd.decoder"))
        assertFalse(AudioOutputPolicy.isUnsafeFloatFlacDecoder(""))
    }
}
