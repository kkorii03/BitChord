package com.music.bitchord.ko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KoDebugQueueTest {

    @Test
    fun `smoke ids keep their order`() {
        assertEquals(
            listOf("-RJSbO8UZVY", "QYh6mYIJG2Y", "v59g4HRNIkc"),
            parseDebugQueueIds("-RJSbO8UZVY,QYh6mYIJG2Y,v59g4HRNIkc"),
        )
    }

    @Test
    fun `spaces and carriage returns around ids are ignored`() {
        assertEquals(
            listOf("abc", "def", "ghi"),
            parseDebugQueueIds(" abc , def\r, ghi \r\n"),
        )
    }

    @Test
    fun `blank entries and a blank list are dropped`() {
        assertEquals(listOf("abc", "def"), parseDebugQueueIds("abc,, ,def,"))
        assertNull(parseDebugQueueIds(null))
        assertNull(parseDebugQueueIds(""))
        assertNull(parseDebugQueueIds(" , \r\n"))
    }
}
