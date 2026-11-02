package com.music.bitchord

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The listen-together latency placeholders take a Long at every call site.
 * A locale that declares `%.1f` (or any other conversion) throws
 * IllegalFormatConversionException at runtime, so format every locale here.
 */
class ListenTogetherStringsFormatTest {

    companion object {
        private val keys = listOf(
            "listen_together_ping",
            "listen_together_server_online",
            "listen_together_server_online_fallback",
            "listen_together_in_sync",
            "listen_together_status_custom_online",
            "listen_together_status_custom_fallback",
        )

        private val resDir: File by lazy {
            val projectRoot = File(".").canonicalFile
            if (File(projectRoot, "app/src/main/res").exists()) {
                File(projectRoot, "app/src/main/res")
            } else {
                File(projectRoot, "src/main/res")
            }
        }
    }

    @Test
    fun everyLocaleFormatsLatencyWithLong() {
        val files = resDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("values") && File(file, "strings.xml").isFile
        }.orEmpty()
        assertTrue("no values*/strings.xml found under $resDir", files.isNotEmpty())

        for (dir in files) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(dir, "strings.xml"))
            for (key in keys) {
                val nodes = doc.getElementsByTagName("string")
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    if (element.getAttribute("name") != key) continue
                    val pattern = element.textContent
                    try {
                        String.format(Locale.ROOT, pattern, 12L)
                    } catch (e: Exception) {
                        throw AssertionError(
                            "${dir.name}/$key cannot format a Long (the call-site type): $pattern",
                            e,
                        )
                    }
                }
            }
        }
    }
}
