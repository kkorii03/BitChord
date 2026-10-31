package com.music.bitchord.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The validated-session marker must be read before every read or write. A login
 * that completes before the startup read has to persist, and a logout has to
 * clear the marker that is actually on disk rather than only an in-memory
 * default.
 */
class TelegramSessionMarkerStateTest {

    @Test
    fun `a read loads the persisted marker before answering`() {
        var loads = 0
        val state = TelegramSessionMarkerState(
            load = { loads++; true },
            mark = {},
            clearPersisted = {},
        )

        assertTrue(state.isValidated())
        assertEquals(1, loads)
        assertEquals("later reads reuse the loaded value", 1, loads)
        state.isValidated()
        assertEquals(1, loads)
    }

    @Test
    fun `a logout loads the earlier marker and clears it on disk`() {
        var persisted = true
        var loads = 0
        val state = TelegramSessionMarkerState(
            load = { loads++; persisted },
            mark = { persisted = true },
            clearPersisted = { persisted = false },
        )

        state.clear()

        assertEquals("clear must read the marker it is about to replace", 1, loads)
        assertFalse(persisted)
        assertFalse(state.isValidated())
    }

    @Test
    fun `a login before the startup read persists the marker`() {
        var persisted = false
        var loads = 0
        val state = TelegramSessionMarkerState(
            load = { loads++; persisted },
            mark = { persisted = true },
            clearPersisted = { persisted = false },
        )

        state.markValidated()

        assertEquals(1, loads)
        assertTrue(persisted)
        assertTrue(state.isValidated())
    }
}
