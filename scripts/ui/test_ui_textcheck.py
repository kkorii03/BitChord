"""Unit tests for scripts/ui/ui_textcheck.py. Run with `python3 -m unittest`."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ui_textcheck

NODE = '<node text="{text}" class="android.widget.TextView" package="com.dev.bitchord" bounds="{bounds}"/>'


def screen(*nodes: str) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0">'
        '<node text="" class="android.widget.FrameLayout" package="com.dev.bitchord" bounds="[0,0][1080,2400]">'
        + "".join(nodes)
        + "</node></hierarchy>"
    )


def node(text: str, bounds: str) -> str:
    return NODE.format(text=text, bounds=bounds)


class CheckerTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.terms = {"settings"}

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def check(self, xml: str) -> list[ui_textcheck.Issue]:
        path = self.root / "screen.xml"
        path.write_text(screen(xml), encoding="utf-8")
        return ui_textcheck.check_screen(path, self.terms)

    def kinds(self, xml: str) -> set[str]:
        return {i.kind for i in self.check(xml)}

    def test_ellipsis_inside_narrow_box_is_clipped(self) -> None:
        issues = self.check(node("이것은 아주 긴 제목입니다…", "[10,10][120,60]"))
        self.assertIn("a-cut", {i.kind for i in issues})

    def test_ellipsis_that_fits_is_not_reported(self) -> None:
        # The box is wide enough for the whole label; the ellipsis is the
        # word's own punctuation, not a truncation.
        issues = self.check(node("더 보기…", "[10,10][400,80]"))
        self.assertNotIn("a-cut", {i.kind for i in issues})

    def test_long_text_in_a_single_line_box_is_cut(self) -> None:
        issues = self.check(node("아주 긴 제목이 상자 밖으로 잘려 나갑니다", "[10,10][140,60]"))
        self.assertIn("a-cut", {i.kind for i in issues})

    def test_wrapped_hangul_word_is_reported(self) -> None:
        # No newline in the dump: the layout wrapped a single long Korean word.
        issues = self.check(node("아주긴한국어단어입니다", "[10,10][120,120]"))
        self.assertIn("b-split-word", {i.kind for i in issues})

    def test_one_or_two_character_line_is_reported(self) -> None:
        xml = node("한국어&#10;단어&#10;가", "[10,10][400,200]")
        self.assertIn("f-short-line", self.kinds(xml))

    def test_newline_inside_hangul_word_is_split(self) -> None:
        xml = node("한국어&#10;단어", "[10,10][300,180]")
        self.assertIn("b-split-word", self.kinds(xml))

    def test_newline_at_a_space_is_not_a_split(self) -> None:
        xml = node("한국어&#10; 단어", "[10,10][300,180]")
        self.assertNotIn("b-split-word", self.kinds(xml))

    def test_overlapping_text_nodes_are_reported(self) -> None:
        xml = node("겹치는 텍스트", "[10,220][400,300]") + node("다른 텍스트", "[20,240][380,320]")
        self.assertIn("c-overlap", self.kinds(xml))

    def test_english_ui_with_korean_translation_is_reported(self) -> None:
        self.assertIn("d-english", self.kinds(node("Settings", "[10,10][400,80]")))

    def test_song_and_brand_names_are_not_reported(self) -> None:
        xml = node("Dance Monkey", "[10,10][400,80]") + node("Spotify", "[10,100][400,170]")
        self.assertNotIn("d-english", self.kinds(xml))

    def test_proper_noun_substring_does_not_hide_untranslated_text(self) -> None:
        # "cast" is inside "Broadcast", but the string still has a Korean
        # translation, so the English must be reported.
        self.terms = {"broadcast settings"}
        issues = self.check(node("Broadcast settings", "[10,10][400,80]"))
        self.assertIn("d-english", {i.kind for i in issues})

    def test_list_rows_with_size_jumps_are_reported(self) -> None:
        xml = (
            '<node text="" class="android.widget.LinearLayout" bounds="[0,500][1080,800]">'
            + node("첫 행", "[10,510][400,558]")
            + node("둘째 행", "[10,580][400,652]")
            + "</node>"
        )
        self.assertIn("e-size-jump", self.kinds(xml))

    def test_two_line_row_is_not_a_size_jump(self) -> None:
        # Same font: one row wraps to two lines, the other stays on one. The
        # per-line height is equal, so this is not a size jump.
        xml = (
            '<node text="" class="android.widget.LinearLayout" bounds="[0,500][1080,800]">'
            + node("첫째 행은&#10;두 줄입니다", "[10,510][400,610]")
            + node("둘째 행", "[10,620][400,670]")
            + "</node>"
        )
        self.assertNotIn("e-size-jump", self.kinds(xml))

    def test_report_lists_every_kind_and_is_markdown(self) -> None:
        xml = (
            node("이것은 아주 긴 제목입니다…", "[10,10][120,60]")
            + node("한국어&#10;단어", "[10,100][300,180]")
            + node("Settings", "[10,200][400,260]")
        )
        report = ui_textcheck.render([("01-screen.xml", self.check(xml))])
        self.assertIn("# UI text check", report)
        self.assertIn("01-screen.xml", report)
        self.assertIn("**a-cut**", report)
        self.assertIn("**b-split-word**", report)
        self.assertIn("**d-english**", report)

    def test_report_uses_korean_from_text_not_from_issue_kinds(self) -> None:
        path = self.root / "korean.xml"
        path.write_text(screen(node("설정", "[10,10][400,80]")), encoding="utf-8")
        self.assertTrue(ui_textcheck.screen_has_hangul(path))
        report = ui_textcheck.render([("korean.xml", [])], has_hangul=True)
        self.assertNotIn("No Korean text found", report)

    def test_untranslated_loader_only_uses_translated_strings(self) -> None:
        for part in ("app/src/main/res", "sharedUi/src/commonMain/composeResources"):
            base = self.root / part
            (base / "values").mkdir(parents=True)
            (base / "values-ko").mkdir(parents=True)
            (base / "values/strings.xml").write_text(
                '<resources><string name="settings">Settings</string>'
                '<string name="spotify">Spotify</string></resources>',
                encoding="utf-8",
            )
            (base / "values-ko/strings.xml").write_text(
                '<resources><string name="settings">설정</string>'
                '<string name="spotify">Spotify</string></resources>',
                encoding="utf-8",
            )
        terms = ui_textcheck.load_untranslated_terms(self.root)
        self.assertIn("settings", terms)
        self.assertNotIn("spotify", terms)


if __name__ == "__main__":
    unittest.main()
