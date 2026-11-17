package com.music.bitchord.download

import com.music.bitchord.TestFiles
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automatic high-quality pass fetches, verifies and decodes whole tracks,
 * so it must wait out the phone's power saving. A pass the listener started
 * with the button is a one-off instruction and keeps going.
 *
 * The same holds for the automatic better-copy look of the playing track: it
 * searches, auditions, may send audio to be heard and downloads a whole
 * lossless file. A manual request still runs.
 */
class HqLibraryLowPowerPolicyTest {

    @Test
    fun `the automatic pass waits out power saving`() {
        assertFalse(hqPassAllowsLowPower(manualPass = false, lowPower = true))
    }

    @Test
    fun `the automatic pass runs out of power saving`() {
        assertTrue(hqPassAllowsLowPower(manualPass = false, lowPower = false))
    }

    @Test
    fun `a pass the listener started ignores power saving`() {
        assertTrue(hqPassAllowsLowPower(manualPass = true, lowPower = true))
        assertTrue(hqPassAllowsLowPower(manualPass = true, lowPower = false))
    }

    @Test
    fun `the automatic look for a better copy waits out low power`() {
        assertFalse(automaticHqLookAllowed(manual = false, heavyWorkAllowed = false))
    }

    @Test
    fun `a manual better-copy request ignores low power`() {
        assertTrue(automaticHqLookAllowed(manual = true, heavyWorkAllowed = false))
    }

    @Test
    fun `the automatic look for a better copy runs out of low power`() {
        assertTrue(automaticHqLookAllowed(manual = false, heavyWorkAllowed = true))
    }

    /** PlaybackService cannot run on the JVM; check the gate sits in the right place. */
    @Test
    fun `lookForBetterCopy gates after the cached-copy audit and before the pending check`() {
        val source = File(
            TestFiles.repositoryRoot(),
            "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt",
        ).readText()
        val start = source.indexOf("private fun lookForBetterCopy(")
        assertTrue("lookForBetterCopy not found", start >= 0)
        val body = source.substring(start)
        val audit = body.indexOf("HqAudioVerify.auditCachedCopy(")
        val gate = body.indexOf("automaticHqLookAllowed(")
        val pending = body.indexOf("QualityUpgrade.isPending(")
        assertTrue("audit not found", audit >= 0)
        assertTrue("low-power gate missing", gate >= 0)
        assertTrue("pending check not found", pending >= 0)
        assertTrue("the audit is a right-song safeguard and must run first", audit < gate)
        assertTrue("the gate must close before any look starts", gate < pending)
    }
}
