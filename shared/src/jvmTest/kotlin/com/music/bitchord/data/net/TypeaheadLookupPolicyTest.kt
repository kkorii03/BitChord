package com.music.bitchord.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypeaheadLookupPolicyTest {

    @Test
    fun `the text still on screen is the one asked about`() {
        assertEquals("coldplay", typeaheadLookupTarget("coldplay", "coldplay", submitted = false))
    }

    @Test
    fun `a keystroke typed past is not asked about`() {
        assertNull(typeaheadLookupTarget("cold", "coldplay", submitted = false))
    }

    @Test
    fun `a committed or blank query is not asked about`() {
        assertNull(typeaheadLookupTarget("coldplay", "coldplay", submitted = true))
        assertNull(typeaheadLookupTarget("   ", "   ", submitted = false))
        assertNull(typeaheadLookupTarget("", "", submitted = false))
    }

    @Test
    fun `surrounding space does not make a second cache entry`() {
        assertEquals("coldplay", typeaheadCacheKey("  coldplay "))
        assertEquals(typeaheadCacheKey("coldplay"), typeaheadCacheKey("coldplay  "))
    }
}
