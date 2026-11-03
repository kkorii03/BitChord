package com.music.bitchord.ui

import com.music.bitchord.ui.replay.formatMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
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
        assertEquals("다음 항목", value("tv", "tv_up_next"))
        assertEquals("나중에 재생", value("tv", "tv_play_last"))
        assertEquals("다음 항목 재생", value("wearos", "up_next"))
    }

    @Test
    fun telegramSourceSignInWarningUsesHapnidaStyle() {
        assertEquals(
            "이 소스를 사용하려면 텔레그램에 로그인합니다.",
            value("app", "telegram_source_login_required"),
        )
    }

    @Test
    fun advancedSyncFooterUsesTheKoreanQualityTerm() {
        val subtitle = value("app", "sync_safety_hq_sync_subtitle").orEmpty()
        assertFalse(subtitle.contains("HQ"))
        assertTrue(subtitle.contains("고음질"))
    }

    @Test
    fun playlistScreensUseTheAppleMusicTerm() {
        assertEquals("플레이리스트", value("tv", "tv_playlists"))
        assertEquals("새 플레이리스트", value("tv", "tv_new_playlist"))
        assertEquals("플레이리스트에 추가", value("tv", "tv_add_to_playlist"))
    }

    @Test
    fun crawledLabelsUseOneAppleMusicTermAcrossScreens() {
        // 11-03 crawl: the shelf says 내 분위기 찾기 but the screen title said
        // 분위기 찾기, and the liked-song action said 음악 where every list says 노래.
        assertEquals("내 분위기 찾기", value("app", "find_your_mood"))
        assertEquals("내 분위기 찾기", value("sharedUi", "find_your_mood"))
        assertEquals("좋아요 표시한 노래에서 제거", value("app", "remove_from_liked"))
        assertEquals("좋아요 표시한 노래에서 제거", value("sharedUi", "remove_from_liked"))
        // For You is a noun label in Apple Music, not a verb instruction.
        assertEquals("나를 위한 추천", value("app", "personalized_recommendations"))
        assertEquals("나를 위한 추천", value("sharedUi", "personalized_recommendations"))
        // Shelf title uses the display joiner, not the awkward "X 외 비슷한".
        assertEquals("%1\$s & 비슷한 아티스트", value("sharedUi", "top_pick_and_similar"))
    }

    @Test
    fun replayMinutesPutTheUnitOnTheValueOnlyWhereTheLabelLacksIt() {
        // Replay card: Apple Music puts the unit on the value, not in the label.
        assertEquals("청취 시간", value("app", "minutes_listened"))
        assertEquals("%1\$s분", value("app", "minutes_listened_value"))
        // Every other locale keeps the unit in the label, so its value must not
        // repeat it (ja 再生時間（分） + 1,237分 was the review finding).
        assertEquals("%1\$s", defaultValue("minutes_listened_value"))
        assertEquals("%1\$s", localeValue("ja", "minutes_listened_value"))
        assertEquals("%1\$s", localeValue("zh", "minutes_listened_value"))
        val koreanValue = String.format(
            Locale.KOREA,
            value("app", "minutes_listened_value").orEmpty(),
            formatMinutes(1_237L * 60_000),
        )
        assertEquals("1,237분", koreanValue)
    }

    @Test
    fun v19KeepsTheForkWordingAndTheNewEnglishMeaning() {
        val problems = mutableListOf<String>()
        fun expect(module: String, key: String, expected: String) {
            val actual = value(module, key)
            if (actual != expected) problems += "$module/$key: expected '$expected' but was '$actual'"
        }
        fun expectContains(module: String, key: String, needle: String) {
            val actual = value(module, key).orEmpty()
            if (!actual.contains(needle)) problems += "$module/$key must contain '$needle': $actual"
        }

        // 1.9 replaced the fork's 매칭 with the literal 일치, which Korean
        // listeners do not use for songs paired across sources.
        expect("app", "source_matching", "소스 매칭")
        expect("app", "spotify_import_matching", "노래 매칭 중(%1\$d/%2\$d)")
        expect("app", "spotify_import_loose", "%1\$d곡은 대략적으로 매칭되었습니다.")
        expectContains("app", "jiosaavn_mismatch_warning", "매칭")
        expectContains("app", "upgrade_length_slack_footer", "매칭")
        expectContains("app", "upgrade_length_slack_footer", "5초")
        // The English still names the FIR decimator and the offset; 1.9 dropped
        // or mistranslated both.
        expect("app", "pipeline_dsd_decoder", "DSD → PCM (네이티브 FIR 데시메이터)")
        expect("app", "pipeline_fir_decimation", "FIR 데시메이션 ×%1\$d, %2\$d탭")
        expect("app", "pipeline_source_verified", "출처: %1\$s · 검증됨(오프셋 %2\$s ms)")
        expectContains("app", "sync_safety_hq_sync_subtitle", "오프셋")
        // These sentences lost the part that told the listener what happens next.
        expectContains("app", "dolby_atmos_unavailable", "일반 버전으로 재생")
        expectContains("app", "proxy_gate_fallback", "한국 제한 우회")
        expectContains("app", "spotify_canvas_auto_hide_subtitle", "하프 플레이어")
        expectContains("app", "addon_url_description", "manifest.json")
        expectContains("app", "source_addon_not_addon", "manifest.json")
        expect("app", "show_all_again", "모두 다시 보기")
        expect("app", "lyrics_share_story_revert", "카드로 돌아가기")
        // 1.9 changed the English meaning of these two without changing the copy.
        expectContains("sharedUi", "automix_not_supported_video", "미지원")
        if (value("sharedUi", "automix_not_supported_video").orEmpty().contains("크로스페이드")) {
            problems += "sharedUi/automix_not_supported_video still says 크로스페이드"
        }
        expectContains("sharedUi", "autoplay_empty_description", "계속 재생")

        assertTrue(
            "1.9 dropped or mistranslated the fork copy:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    private fun defaultValue(key: String): String? =
        strings(File(root, "app/src/main/res/values/strings.xml"))[key]

    private fun localeValue(qualifier: String, key: String): String? =
        strings(File(root, "app/src/main/res/values-$qualifier/strings.xml"))[key]

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
