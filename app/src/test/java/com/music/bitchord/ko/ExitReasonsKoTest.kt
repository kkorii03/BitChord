package com.music.bitchord.ko

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitReasonsKoTest {

    @Test
    fun `reason ids become the names an exported log reads`() {
        assertEquals("ANR", exitReasonName(ApplicationExitInfo.REASON_ANR))
        assertEquals("CRASH", exitReasonName(ApplicationExitInfo.REASON_CRASH))
        assertEquals("LOW_MEMORY", exitReasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("CRASH_NATIVE", exitReasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("OTHER(99)", exitReasonName(99))
    }

    @Test
    fun `an exit line carries reason description time and pss`() {
        val line = formatExitLine(
            reason = ApplicationExitInfo.REASON_ANR,
            atMs = 1_700_000_000_000L,
            description = "Input dispatch timeout\n(main thread)",
            pssKb = 123_456L,
        )
        assertTrue(line, line.startsWith("exit-reason: ANR Input dispatch timeout (main thread) at "))
        assertTrue(line, line.endsWith("pss=123456"))
    }

    @Test
    fun `a missing description is written as a dash`() {
        val line = formatExitLine(ApplicationExitInfo.REASON_CRASH, 0L, null, 0L)
        assertTrue(line, line.contains(" CRASH - at "))
    }

    @Test
    fun `only the main thread's section of an ANR trace is taken`() {
        val trace = """
            ----- pid 123 -----
            Cmd line: com.music.bitchord
            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1 dsCount=0
              at android.os.MessageQueue.nativePollOnce(Native Method)
              at com.music.bitchord.MainActivity.onCreate(MainActivity.kt:1)
            "Binder:123_1" prio=5 tid=2 Native
              at android.os.BinderProxy.transact(Native Method)
        """.trimIndent()

        val lines = mainThreadTraceLines(trace.lineSequence())
        assertEquals(
            listOf(
                "\"main\" prio=5 tid=1 Blocked",
                "| group=\"main\" sCount=1 dsCount=0",
                "at android.os.MessageQueue.nativePollOnce(Native Method)",
                "at com.music.bitchord.MainActivity.onCreate(MainActivity.kt:1)",
            ),
            lines,
        )
    }

    @Test
    fun `a trace without a main thread yields nothing and the cap holds`() {
        assertTrue(mainThreadTraceLines(sequenceOf("\"Binder:1\" tid=2", "  at x")).isEmpty())

        val many = sequenceOf("\"main\" tid=1") +
            (1..100).asSequence().map { "  at frame$it" }
        assertEquals(10, mainThreadTraceLines(many, maxLines = 10).size)
    }
}
