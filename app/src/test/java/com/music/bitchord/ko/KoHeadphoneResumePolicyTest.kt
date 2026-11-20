package com.music.bitchord.ko

import androidx.media3.common.Player
import com.music.bitchord.playback.AudioRouting
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KO] P1-1: headphones must not undo a sleep-timer or focus-loss pause. */
class KoHeadphoneResumePolicyTest {

    private val window = 30L * 60L * 1000L
    private val now = 100L * 60L * 1000L
    private val tenMinutesAgo = now - 10L * 60L * 1000L

    @Test
    fun `a fresh pause the listener made may resume`() {
        assertTrue(KoHeadphoneResumePolicy.mayResume(tenMinutesAgo, now, vetoed = false, windowMs = window))
    }

    @Test
    fun `a fresh pause the sleep timer made may not resume`() {
        assertFalse(KoHeadphoneResumePolicy.mayResume(tenMinutesAgo, now, vetoed = true, windowMs = window))
    }

    @Test
    fun `an old pause or one that never happened may not resume`() {
        assertFalse(KoHeadphoneResumePolicy.mayResume(now - window - 1, now, vetoed = false, windowMs = window))
        assertFalse(KoHeadphoneResumePolicy.mayResume(0L, now, vetoed = false, windowMs = window))
        assertTrue(KoHeadphoneResumePolicy.mayResume(now - window, now, vetoed = false, windowMs = window))
    }

    @Test
    fun `only a permanent focus loss pause vetoes`() {
        assertTrue(
            KoHeadphoneResumePolicy.vetoesPause(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS),
        )
        assertFalse(
            KoHeadphoneResumePolicy.vetoesPause(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST),
        )
        assertFalse(
            KoHeadphoneResumePolicy.vetoesPause(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY),
        )
        assertFalse(
            KoHeadphoneResumePolicy.vetoesPause(true, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS),
        )
    }

    // [KO] btroute, 11-20 17:20: earbuds gone during an output rebuild, 9 s on the phone speaker.

    @Test
    fun `headphones gone with the music falling to the speaker pauses`() {
        assertTrue(KoHeadphoneResumePolicy.pausesOnRouteLoss(true, AudioRouting.Kind.BLUETOOTH, AudioRouting.Kind.PHONE))
        assertTrue(KoHeadphoneResumePolicy.pausesOnRouteLoss(true, AudioRouting.Kind.WIRED, AudioRouting.Kind.PHONE))
        assertTrue(KoHeadphoneResumePolicy.pausesOnRouteLoss(true, AudioRouting.Kind.USB, AudioRouting.Kind.PHONE))
        assertTrue(KoHeadphoneResumePolicy.pausesOnRouteLoss(true, AudioRouting.Kind.BLUETOOTH, null))
    }

    @Test
    fun `a route that stays off the speaker, or was on it, or is paused, does not pause`() {
        // BT gone with a USB DAC or wired headphones still in: the music moves there.
        assertFalse(KoHeadphoneResumePolicy.pausesOnRouteLoss(true, AudioRouting.Kind.BLUETOOTH, AudioRouting.Kind.USB))
        assertFalse(KoHeadphoneResumePolicy.pausesOnRouteLoss(true, AudioRouting.Kind.BLUETOOTH, AudioRouting.Kind.WIRED))
        // Only the headset profile (SCO) went: A2DP is still the route.
        assertFalse(KoHeadphoneResumePolicy.pausesOnRouteLoss(true, AudioRouting.Kind.BLUETOOTH, AudioRouting.Kind.BLUETOOTH))
        // Already on the speaker (chosen there), or nothing known about where it was.
        assertFalse(KoHeadphoneResumePolicy.pausesOnRouteLoss(true, AudioRouting.Kind.PHONE, AudioRouting.Kind.PHONE))
        assertFalse(KoHeadphoneResumePolicy.pausesOnRouteLoss(true, null, AudioRouting.Kind.PHONE))
        assertFalse(KoHeadphoneResumePolicy.pausesOnRouteLoss(false, AudioRouting.Kind.BLUETOOTH, AudioRouting.Kind.PHONE))
    }

    @Test
    fun `the device callback pauses before the output rebuild is asked for`() {
        val root = File(".").canonicalFile
        val service = listOf("app/", "")
            .map { File(root, "${it}src/main/java/com/music/bitchord/playback/PlaybackService.kt") }
            .first { it.exists() }
            .readText()
        val removed = service
            .substringAfter("override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {")
            .substringBefore("requestOutputReconfiguration()")
        val capture = removed.indexOf("val chosenBefore = AudioRouting.selectedId.value")
        val pause = removed.indexOf("pauseIfFallingToSpeaker(removedDevices, chosenBefore)")
        assertTrue("callback must consult the route-loss rule", pause >= 0)
        assertTrue("the choice is read before it is forgotten", capture in 0 until removed.indexOf("AudioRouting.forget()"))
        val helper = service
            .substringAfter("private fun pauseIfFallingToSpeaker(")
            .substringBefore("\n    }\n")
        assertTrue(helper.contains("KoHeadphoneResumePolicy.pausesOnRouteLoss(active.playWhenReady, was, now)"))
        assertTrue(helper.contains("active.playWhenReady = false"))
        // The rebuild still carries the old player's play state: the pause above is what it copies.
        val rebuild = service.substringAfter("private fun rebuildPlayersForOutput(").substringBefore("oldActive.release()")
        assertTrue(rebuild.contains("val playWhenReady = oldActive.playWhenReady"))
        assertTrue(rebuild.contains("newActive.playWhenReady = playWhenReady"))
    }
}
