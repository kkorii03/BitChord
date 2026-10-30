package com.music.bitchord.playback.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate an addon reads as `checkValidLossless`.
 *
 * It is the only place that decides whether the phone currently has an output
 * that can carry lossless audio, and it is what makes a source offer its FLAC
 * copy at all. A wrong answer here therefore changes which stream is fetched,
 * not just what a badge says.
 */
class LosslessOutputTest {

    private fun decide(vararg outputs: Int, codec: String? = null, rate: Int? = null) =
        LosslessOutput.decide(outputs.toSet(), codec, rate)

    @Test
    fun `the phone speaker and earpiece are not lossless outputs`() {
        listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            .forEach { type ->
                val state = decide(type)
                assertFalse("type $type", state.capable)
                assertNull(state.via)
            }
    }

    @Test
    fun `wired headphones and line outputs count`() {
        listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to LosslessOutput.Kind.WIRED,
            AudioDeviceInfo.TYPE_WIRED_HEADSET to LosslessOutput.Kind.WIRED,
            AudioDeviceInfo.TYPE_LINE_ANALOG to LosslessOutput.Kind.WIRED,
            AudioDeviceInfo.TYPE_LINE_DIGITAL to LosslessOutput.Kind.WIRED,
            AudioDeviceInfo.TYPE_AUX_LINE to LosslessOutput.Kind.WIRED,
        ).forEach { (type, kind) ->
            val state = decide(type)
            assertTrue("type $type", state.capable)
            assertEquals(kind, state.via)
        }
    }

    @Test
    fun `usb audio devices count`() {
        listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET to LosslessOutput.Kind.USB,
            AudioDeviceInfo.TYPE_USB_DEVICE to LosslessOutput.Kind.USB,
            AudioDeviceInfo.TYPE_USB_ACCESSORY to LosslessOutput.Kind.USB,
        ).forEach { (type, kind) ->
            val state = decide(type)
            assertTrue("type $type", state.capable)
            assertEquals(kind, state.via)
        }
    }

    @Test
    fun `hdmi arc earc and docks count`() {
        listOf(
            AudioDeviceInfo.TYPE_HDMI to LosslessOutput.Kind.HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC to LosslessOutput.Kind.HDMI,
            29 to LosslessOutput.Kind.HDMI,
            AudioDeviceInfo.TYPE_DOCK to LosslessOutput.Kind.DOCK,
            31 to LosslessOutput.Kind.DOCK,
        ).forEach { (type, kind) ->
            val state = decide(type)
            assertTrue("type $type", state.capable)
            assertEquals(kind, state.via)
        }
    }

    @Test
    fun `a cable wins over a bluetooth route that is also connected`() {
        val state = decide(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            codec = "LDAC",
        )

        assertTrue(state.capable)
        assertEquals(LosslessOutput.Kind.USB, state.via)
        assertEquals("LDAC", state.bluetoothCodec)
        assertFalse(state.bluetoothCodecUnknown)
    }

    @Test
    fun `bluetooth with no codec named is reported as unknown rather than guessed`() {
        val state = decide(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

        assertFalse(state.capable)
        assertNull(state.via)
        assertNull(state.bluetoothCodec)
        assertTrue(state.bluetoothCodecUnknown)
    }

    @Test
    fun `a named codec is kept even when it is lossy`() {
        listOf("SBC", "AAC", "aptX", "aptX HD", "LC3", "Opus").forEach { codec ->
            val state = decide(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, codec = codec)
            assertFalse(codec, state.capable)
            assertNull(state.via)
            assertEquals(codec, state.bluetoothCodec)
            assertFalse(codec, state.bluetoothCodecUnknown)
        }
    }

    @Test
    fun `ldac lhdc and aptx lossless count by name`() {
        listOf("LDAC", "LHDC", "LHDC V3", "LHDC V5", "aptX Lossless").forEach { codec ->
            val state = decide(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, codec = codec)
            assertTrue(codec, state.capable)
            assertEquals(codec, LosslessOutput.Kind.BLUETOOTH, state.via)
        }
    }

    @Test
    fun `samsung uhq counts by its name`() {
        listOf("SSC UHQ", "Samsung UHQ", "UHQ").forEach { codec ->
            val state = decide(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, codec = codec)
            assertTrue(codec, state.capable)
            assertEquals(LosslessOutput.Kind.BLUETOOTH, state.via)
        }
    }

    @Test
    fun `plain samsung scalable only counts at uhq sample rates`() {
        listOf("SSC", "Samsung Seamless", "Scalable").forEach { codec ->
            assertTrue(codec, decide(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, codec = codec, rate = 88_200).capable)
            assertTrue(codec, decide(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, codec = codec, rate = 96_000).capable)
            assertFalse(codec, decide(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, codec = codec, rate = 88_199).capable)
            assertFalse(codec, decide(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, codec = codec, rate = 44_100).capable)
            assertFalse(codec, decide(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, codec = codec).capable)
        }
    }

    @Test
    fun `le audio is not counted even when a lossless-looking codec is named`() {
        val state = decide(AudioDeviceInfo.TYPE_BLE_HEADSET, codec = "LDAC")

        assertFalse(state.capable)
        assertNull(state.via)
    }

    @Test
    fun `the codec name comparison ignores case`() {
        assertTrue(LosslessOutput.isLosslessBluetoothCodec("ldac"))
        assertTrue(LosslessOutput.isLosslessBluetoothCodec("Lhdc V4"))
        assertTrue(LosslessOutput.isLosslessBluetoothCodec("APTX LOSSLESS"))
        assertTrue(LosslessOutput.isLosslessBluetoothCodec("ssc", 96_000))
        assertFalse(LosslessOutput.isLosslessBluetoothCodec("sbc", 96_000))
    }
}
