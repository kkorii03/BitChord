package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `src:<configId>::<trackId>` key the queue carries for a source-backed
 * track. Parsing it must never mistake an ordinary YouTube id for one, and the
 * first separator is the split because a catalogue id may itself contain `::`.
 */
class SourceTrackKeysTest {

    @Test
    fun `a source key splits at the first separator`() {
        assertEquals("cfg" to "track", SourceTrackKeys.parse("src:cfg::track"))
        assertEquals("cfg" to "a::b", SourceTrackKeys.parse("src:cfg::a::b"))
        assertEquals("cfg" to "a::b::c", SourceTrackKeys.parse("src:cfg::a::b::c"))
    }

    @Test
    fun `ordinary YouTube ids are not source keys`() {
        assertNull(SourceTrackKeys.parse("dQw4w9WgXcQ"))
        assertNull(SourceTrackKeys.parse(""))
        assertNull(SourceTrackKeys.parse("src"))
        assertNull(SourceTrackKeys.parse("src:"))
        assertNull(SourceTrackKeys.parse("src:cfg"))
        assertNull(SourceTrackKeys.parse("src:cfg:track"))
        assertNull(SourceTrackKeys.parse("src:::track"))
        assertNull(SourceTrackKeys.parse("src::track"))
        assertNull(SourceTrackKeys.parse("SRC:cfg::track"))
        assertNull(SourceTrackKeys.parse(" src:cfg::track"))
        assertNull(SourceTrackKeys.parse("yt:src:cfg::track"))
    }

    @Test
    fun `blank halves are returned exactly as written`() {
        // The parser only locates the separator; validating ids belongs to the
        // registry that minted them.
        assertEquals("cfg" to "", SourceTrackKeys.parse("src:cfg::"))
        assertEquals(" " to "track", SourceTrackKeys.parse("src: ::track"))
        assertEquals("cfg" to " ", SourceTrackKeys.parse("src:cfg:: "))
    }

    @Test
    fun `the constants describe the key shape the registry mints`() {
        assertEquals("src:", SourceTrackKeys.PREFIX)
        assertEquals("::", SourceTrackKeys.SEPARATOR)
        assertEquals(
            "cfg" to "id",
            SourceTrackKeys.parse(SourceTrackKeys.PREFIX + "cfg" + SourceTrackKeys.SEPARATOR + "id"),
        )
    }
}
