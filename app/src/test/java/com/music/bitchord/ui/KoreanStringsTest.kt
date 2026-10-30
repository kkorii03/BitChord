package com.music.bitchord.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Korean copy follows Apple Music: noun-shaped labels, formal 합니다체, and the
 * terms 보관함 / 플레이리스트 / 다음 항목 재생 / 나중에 재생 / 셔플.
 *
 * The casual 해요체 that leaked into the TV, watch and lyrics placeholders is a
 * real regression, not a style preference: Korean listeners read the same
 * action as two different voices depending on which screen they are on.
 */
class KoreanStringsTest {

    @Test
    fun koreanCopyHasNoCasualHaeyoEndings() {
        val casual = listOf(
            "하세요", "해 주세요", "해주세요", "보세요", "주세요",
            "있어요", "없어요", "돼요", "할게요", "거예요", "예요", "이에요",
            "나요", "가요",
        )
        for ((name, file) in koreanFiles()) {
            for ((key, text) in strings(file)) {
                val found = casual.firstOrNull { text.contains(it) }
                assertTrue(
                    "$name/$key still uses casual Korean ('$found'): $text",
                    found == null,
                )
            }
        }
    }

    @Test
    fun koreanCopyUsesPlaylistTerm() {
        for ((name, file) in koreanFiles()) {
            for ((key, text) in strings(file)) {
                assertFalse(
                    "$name/$key still says 재생목록 instead of 플레이리스트: $text",
                    text.contains("재생목록"),
                )
            }
        }
    }

    @Test
    fun playNextAndPlayLaterUseTheAppleMusicTerms() {
        assertEquals("다음 항목 재생", value("app", "play_next"))
        assertEquals("나중에 재생", value("app", "add_to_queue"))
        assertEquals("나중에 재생됩니다", value("app", "song_added_to_queue"))
        assertEquals("다음 항목 재생", value("sharedUi", "play_next"))
        assertEquals("다음 항목 재생", value("tv", "tv_play_next"))
        assertEquals("나중에 재생", value("tv", "tv_play_last"))
        assertEquals("다음 항목 재생", value("wearos", "up_next"))
    }

    @Test
    fun playlistScreensUseTheAppleMusicTerm() {
        assertEquals("플레이리스트", value("tv", "tv_playlists"))
        assertEquals("새 플레이리스트", value("tv", "tv_new_playlist"))
        assertEquals("플레이리스트에 추가", value("tv", "tv_add_to_playlist"))
    }

    private fun value(module: String, key: String): String? =
        strings(koreanFiles().first { it.first == module }.second)[key]

    private fun koreanFiles(): List<Pair<String, File>> = listOf(
        "app" to "app/src/main/res/values-ko/strings.xml",
        "sharedUi" to "sharedUi/src/commonMain/composeResources/values-ko/strings.xml",
        "tv" to "tv/src/main/res/values-ko/strings.xml",
        "wearos" to "wearos/src/main/res/values-ko/strings.xml",
    ).map { (module, path) -> module to File(root, path) }

    private fun strings(file: File): Map<String, String> {
        assertTrue("${file.path} must exist", file.exists())
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = linkedMapOf<String, String>()
        val nodes = document.getElementsByTagName("string")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            val name = element.getAttribute("name")
            if (name.isNotEmpty()) result[name] = element.textContent
        }
        val arrays = document.getElementsByTagName("item")
        for (index in 0 until arrays.length) {
            val element = arrays.item(index) as Element
            result["item#$index"] = element.textContent
        }
        return result
    }

    private companion object {
        val root: File by lazy {
            var directory = File(".").canonicalFile
            while (true) {
                if (File(directory, "settings.gradle.kts").exists()) break
                directory = directory.parentFile ?: break
            }
            directory
        }
    }
}
