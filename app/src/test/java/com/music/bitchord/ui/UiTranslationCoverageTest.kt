package com.music.bitchord.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * User-visible strings that used to be English in the Korean build, or were
 * marked untranslatable and therefore could never be translated at all.
 */
class UiTranslationCoverageTest {

    private val required = listOf(
        "pipeline_pcm_data_rate",
        "pipeline_route",
        "pipeline_bit_exact_yes",
        "pipeline_bit_exact_dsd",
        "pipeline_bit_exact_loudness",
        "pipeline_bit_exact_eq",
        "pipeline_bit_exact_spatial",
        "pipeline_bit_exact_downstream",
        "pipeline_bit_exact_downstream_unknown",
        "route_phone",
        "route_wired",
        "route_usb",
        "route_bluetooth",
        "route_hdmi",
        "route_other",
        "spotify_import_language_preference",
        "spotify_import_language_hint",
    )

    @Test
    fun pipelineAndSpotifyLabelsHaveKoreanTranslations() {
        val english = strings("values")
        val korean = strings("values-ko")

        for (key in required) {
            assertNotNull("values/strings.xml is missing $key", english[key])
            assertNotNull("values-ko/strings.xml is missing $key", korean[key])
            assertTrue("$key must be Korean in values-ko", korean.getValue(key).hasHangul())
        }
    }

    @Test
    fun userVisibleLabelsAreNotMarkedUntranslatable() {
        val raw = File(resDir, "values/strings.xml").readText()
        for (key in required) {
            val tag = Regex("""<string name="${Regex.escape(key)}"[^>]*>""").find(raw)
            assertNotNull("$key is missing from values/strings.xml", tag)
            assertFalse("$key is user-visible and must not be translatable=\"false\"", tag!!.value.contains("translatable=\"false\""))
        }
    }

    private fun String.hasHangul(): Boolean = any { it in '\uAC00'..'\uD7A3' }

    private fun strings(qualifier: String): Map<String, String> {
        val file = File(resDir, "$qualifier/strings.xml")
        assertTrue("${file.path} must exist", file.exists())
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            val name = element.getAttribute("name")
            if (name.isNotEmpty()) result[name] = element.textContent
        }
        return result
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
