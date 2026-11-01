package com.music.bitchord.ko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Container probes are memoized so a playback key resolution does not reopen
 * the same complete cache entry every time.
 */
class KoContainerMemoTest {

    private val flac = "flac" to "audio/flac"

    @Test
    fun `an unchanged entry is probed only once`() {
        var probes = 0
        val memo = KoContainerMemo(probe = { probes++; flac }, contentLengthOf = { 100L })

        assertEquals(flac, memo.containerOf("v#alt"))
        assertEquals(flac, memo.containerOf("v#alt"))
        assertEquals(1, probes)
    }

    @Test
    fun `a refilled entry with a new length is probed again`() {
        var length = 100L
        var probes = 0
        val memo = KoContainerMemo(probe = { probes++; flac }, contentLengthOf = { length })

        memo.containerOf("v#alt")
        length = 200L
        memo.containerOf("v#alt")

        assertEquals(2, probes)
    }

    @Test
    fun `a failed probe is not remembered`() {
        var probes = 0
        val memo = KoContainerMemo(probe = { probes++; null }, contentLengthOf = { 100L })

        assertNull(memo.containerOf("v#alt"))
        assertNull(memo.containerOf("v#alt"))

        assertEquals(2, probes)
    }

    @Test
    fun `forgetting a track drops every rendition it had`() {
        var probes = 0
        val memo = KoContainerMemo(probe = { probes++; flac }, contentLengthOf = { 100L })

        memo.containerOf("v#alt")
        memo.forget("v")
        memo.containerOf("v#alt")

        assertEquals(2, probes)
    }
}
