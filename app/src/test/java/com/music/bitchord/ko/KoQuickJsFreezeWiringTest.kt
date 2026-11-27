package com.music.bitchord.ko

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KO] freeze1127, 11-27 16:54:40: four addon modules were built at once on
 * Default for the next song's lead; none of them ever logged its exports, the
 * app stopped making sound, went silent in its own log at 16:55:21 and died
 * with CRASH_NATIVE at 16:55:58. The interpreter needs a native library this
 * JVM test cannot load, so the three rules are pinned on the source.
 */
class KoQuickJsFreezeWiringTest {

    private val source: String by lazy {
        val root = File(".").canonicalFile
        listOf("app/", "")
            .map { File(root, "${it}src/main/java/com/music/bitchord/data/sources/module/QuickJsExecutor.kt") }
            .first { it.exists() }
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `interpreters are built one at a time`() {
        assertTrue(source.contains("private val building = Mutex()"))
        val newEngine = source.substringAfter("private suspend fun newEngine(").substringBefore("\n    }\n")
        assertTrue(newEngine, newEngine.contains("= building.withLock {"))
        assertTrue(newEngine.indexOf("building.withLock") < newEngine.indexOf("QuickJs.create("))
        assertEquals(1, Regex("""QuickJs\.create\(""").findAll(source).count())
    }

    @Test
    fun `every evaluate takes the stack top on the thread it runs on`() {
        assertFalse("a bare qjs.evaluate skips the stack refresh", source.contains("qjs.evaluate<"))
        val helper = source.substringAfter("fun <reified T> QuickJs.evalHere(").substringBefore("\n    }\n")
        assertTrue(helper.indexOf("withNativeContext { }") in 0 until helper.indexOf("evaluate<T>(code)"))
        assertTrue(Regex("""qjs\.evalHere<""").findAll(source).count() >= 7)
    }

    @Test
    fun `a module fetch blocks an IO thread, not the interpreter's`() {
        val binding = source.substringAfter("asyncFunction(\"fetch\"").substringBefore("asyncFunction(\"setTimeout\"")
        val io = binding.indexOf("withContext(Dispatchers.IO)")
        assertTrue(binding, io >= 0)
        assertTrue(io < binding.indexOf("fetchUrlSync(url, method, headersJson, body)"))
    }
}
