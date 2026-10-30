package com.music.bitchord.data.stats

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * HQ plans and verdicts name a sourceConfigId, and the source registry itself
 * is device-local and excluded from backup. Restoring the plans without their
 * sources leaves targets that can never resolve, so both rule sets have to
 * exclude them wherever the source registry is excluded.
 */
class BackupRulesSafetyTest {

    @Test
    fun `legacy auto backup excludes hq plans and verdicts with the source registry`() {
        val paths = excludes("backup_rules.xml")

        assertEquals(1, paths.count { it == "bitchord_sources.xml" })
        assertEquals(1, paths.count { it == "hq_library.xml" })
        assertEquals(1, paths.count { it == "hq_verification.xml" })
    }

    @Test
    fun `android 12 cloud backup and device transfer exclude hq plans and verdicts`() {
        val paths = excludes("data_extraction_rules.xml")

        assertEquals(2, paths.count { it == "bitchord_sources.xml" })
        assertEquals(2, paths.count { it == "hq_library.xml" })
        assertEquals(2, paths.count { it == "hq_verification.xml" })
    }

    private fun excludes(name: String): List<String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(resDir, "xml/$name"))
        val nodes = document.getElementsByTagName("exclude")
        return (0 until nodes.length).map { index ->
            (nodes.item(index) as Element).getAttribute("path")
        }
    }

    private companion object {
        val resDir: File by lazy {
            val projectRoot = File(".").canonicalFile
            if (File(projectRoot, "app/src/main/res").exists()) {
                File(projectRoot, "app/src/main/res")
            } else {
                File(projectRoot, "src/main/res")
            }
        }
    }
}
