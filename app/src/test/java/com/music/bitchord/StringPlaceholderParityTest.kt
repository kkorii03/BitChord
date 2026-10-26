package com.music.bitchord

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A translation may reorder or drop a placeholder only if the code that calls
 * it knows; a `%1$s` that disappears from the Korean copy makes the screen show
 * a sentence with a hole in it, and one that appears without a matching
 * argument crashes at format time. Every shared string and every shared plural
 * item is compared here, not just the ones this task touched.
 */
class StringPlaceholderParityTest {

    @Test
    fun everyKoreanStringKeepsTheBasePlaceholders() {
        val base = load("values")
        val korean = load("values-ko")
        val problems = mutableListOf<String>()

        for ((key, koreanItems) in korean) {
            val baseItems = base[key]
            if (baseItems == null) {
                problems += "$key exists in values-ko but not in values"
                continue
            }

            for ((item, koreanText) in koreanItems) {
                val baseText = baseItems[item]
                if (baseText == null) {
                    problems += "$key${itemLabel(item)} exists in values-ko but not in values"
                    continue
                }

                val basePlaceholders = placeholders(baseText)
                val koreanPlaceholders = placeholders(koreanText)
                if (basePlaceholders != koreanPlaceholders) {
                    problems += "$key${itemLabel(item)}: values=$basePlaceholders values-ko=$koreanPlaceholders"
                }
            }
        }

        assertTrue(
            "values-ko placeholder drift:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun valuesKoDoesNotAddPlaceholdersTheBaseDoesNotHave() {
        val base = load("values")
        val korean = load("values-ko")
        val problems = mutableListOf<String>()

        for ((key, koreanItems) in korean) {
            val baseItems = base[key] ?: continue
            for ((item, koreanText) in koreanItems) {
                val baseText = baseItems[item] ?: continue
                val added = placeholders(koreanText) - placeholders(baseText).toSet()
                if (added.isNotEmpty()) {
                    problems += "$key${itemLabel(item)} adds $added"
                }
            }
        }

        assertTrue(
            "values-ko has placeholders missing from values:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    private fun itemLabel(item: String): String = if (item.isEmpty()) "" else "[$item]"

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    private fun load(qualifier: String): Map<String, Map<String, String>> {
        val file = File(resDir, "$qualifier/strings.xml")
        assertTrue("${file.path} must exist", file.exists())
        val document = parse(file)
        val result = linkedMapOf<String, Map<String, String>>()

        for (tagName in listOf("string", "plurals", "string-array")) {
            val nodes = document.getElementsByTagName(tagName)
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                val name = element.getAttribute("name")
                if (name.isEmpty()) continue

                result[name] = when (tagName) {
                    "string" -> mapOf("" to element.textContent)
                    "plurals" -> pluralItems(element)
                    else -> arrayItems(element)
                }
            }
        }

        return result
    }

    private fun pluralItems(element: Element): Map<String, String> {
        val items = linkedMapOf<String, String>()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            if (child.tagName != "item") continue
            items[child.getAttribute("quantity")] = child.textContent
        }
        return items
    }

    private fun arrayItems(element: Element): Map<String, String> {
        val items = linkedMapOf<String, String>()
        val children = element.childNodes
        var itemIndex = 0
        for (index in 0 until children.length) {
            val child = children.item(index) as? Element ?: continue
            if (child.tagName != "item") continue
            items[itemIndex.toString()] = child.textContent
            itemIndex++
        }
        return items
    }

    private fun parse(file: File): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private companion object {
        val PLACEHOLDER = Regex("""%(?:\d+\$)?[a-zA-Z]""")

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
