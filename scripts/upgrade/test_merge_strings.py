#!/usr/bin/env python3
"""Unit tests for scripts/upgrade/merge_strings.py (stdlib unittest only)."""

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("merge_strings.py")
SPEC = importlib.util.spec_from_file_location("merge_strings", SCRIPT)
merge_strings = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = merge_strings
SPEC.loader.exec_module(merge_strings)


BASE = r'''<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools">
    <!-- base-only header -->
    <string name="unchanged">Base unchanged</string>
    <string name="ours_changed">Base ours</string>
    <string name="theirs_changed">Base theirs</string>
    <string name="both_same">Base same</string>
    <string name="both_diff">Base diff</string>
    <string name="removed_drop">Base drop</string>
    <string name="removed_keep">Base keep</string>
    <string name="markup" translatable="false" tools:ignore="TypographyEllipsis">Base <b>bold</b> \'q\' %1$s</string>
    <plurals name="song_count">
        <item quantity="other">Base %1$d songs</item>
    </plurals>
    <string-array name="letters">
        <item>Base A</item>
        <item>Base B</item>
    </string-array>
</resources>
'''

OURS = r'''<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools">
    <!-- ours-only header -->
    <string name="ours_changed">Ours changed</string>
    <string name="unchanged">Base unchanged</string>
    <string name="both_same">Both same</string>
    <string name="both_diff">Ours diff</string>
    <string name="removed_drop">Base drop</string>
    <string name="removed_keep">Ours keep</string>
    <string name="markup" translatable="false" tools:ignore="TypographyEllipsis">Ours <b>bold</b> \'q\' %1$s</string>
    <plurals name="song_count">
        <item quantity="other">Ours %1$d songs</item>
    </plurals>
    <string-array name="letters">
        <item>Ours A</item>
        <item>Ours B</item>
    </string-array>
    <string name="ours_only">Fork only</string>
    <string name="both_added_same">Same add</string>
    <string name="both_added_diff">Ours add</string>
</resources>
'''

THEIRS = r'''<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools">
    <!-- theirs-only header -->
    <string name="unchanged">Base unchanged</string>
    <string name="ours_changed">Base ours</string>
    <string name="theirs_changed">Theirs changed</string>
    <string name="both_same">Both same</string>
    <string name="both_diff">Theirs diff</string>
    <string name="theirs_only">Upstream only</string>
    <string name="both_added_same">Same add</string>
    <string name="both_added_diff">Theirs add</string>
    <string name="markup" translatable="false" tools:ignore="TypographyEllipsis">Base <b>bold</b> \'q\' %1$s</string>
    <plurals name="song_count">
        <item quantity="other">Base %1$d songs</item>
    </plurals>
    <string-array name="letters">
        <item>Theirs A</item>
        <item>Theirs B</item>
    </string-array>
</resources>
'''


class MergeTextsTest(unittest.TestCase):
    def merge(self, korean=False):
        return merge_strings.merge_texts(BASE, OURS, THEIRS, korean)

    def test_theirs_order_and_content(self):
        result = self.merge()
        text = result.text
        self.assertIn("theirs-only header", text)
        self.assertNotIn("base-only header", text)
        self.assertNotIn("ours-only header", text)
        self.assertLess(text.index('name="theirs_changed"'), text.index('name="theirs_only"'))
        self.assertLess(text.index('name="theirs_only"'), text.index('name="ours_only"'))
        self.assertIn('<string name="unchanged">Base unchanged</string>', text)
        self.assertEqual(result.counts["unchanged"], 1)

    def test_side_specific_changes(self):
        text = self.merge().text
        self.assertIn('<string name="ours_changed">Ours changed</string>', text)
        self.assertIn('<string name="theirs_changed">Theirs changed</string>', text)
        self.assertIn('<string name="both_same">Both same</string>', text)
        self.assertIn('<string name="theirs_only">Upstream only</string>', text)

    def test_both_changed_values_ko_keeps_ours(self):
        result = self.merge(korean=True)
        text = result.text
        self.assertIn('<string name="both_diff">Ours diff</string>', text)
        self.assertIn('<string name="both_added_diff">Ours add</string>', text)
        decisions = {name: decision for _, name, decision in result.both_changed}
        self.assertEqual(decisions["both_diff"], "ours")
        self.assertEqual(decisions["both_added_diff"], "ours")

    def test_both_changed_other_locale_keeps_theirs(self):
        result = self.merge(korean=False)
        text = result.text
        self.assertIn('<string name="both_diff">Theirs diff</string>', text)
        self.assertIn('<string name="both_added_diff">Theirs add</string>', text)
        decisions = {name: decision for _, name, decision in result.both_changed}
        self.assertEqual(decisions["both_diff"], "theirs")
        self.assertEqual(decisions["both_added_diff"], "theirs")

    def test_ours_only_is_appended_after_marker(self):
        result = self.merge()
        text = result.text
        marker = text.index("fork-only resources kept by merge_strings.py")
        self.assertLess(marker, text.index('name="ours_only"'))
        self.assertIn('<string name="ours_only">Fork only</string>', text)
        self.assertEqual(result.counts["ours_only_added"], 1)

    def test_removed_upstream_drop_and_keep(self):
        result = self.merge()
        text = result.text
        self.assertNotIn('name="removed_drop"', text)
        self.assertIn('<string name="removed_keep">Ours keep</string>', text)
        self.assertEqual(result.counts["removed_dropped"], 1)
        self.assertEqual(result.counts["removed_kept"], 1)

    def test_plurals_and_arrays(self):
        result = self.merge(korean=False)
        text = result.text
        self.assertIn("<item quantity=\"other\">Ours %1$d songs</item>", text)
        self.assertIn("<item>Theirs A</item>", text)
        ko_text = self.merge(korean=True).text
        self.assertIn("<item>Ours A</item>", ko_text)

    def test_markup_attributes_and_escapes_are_byte_exact(self):
        text = self.merge().text
        self.assertIn(
            '<string name="markup" translatable="false" '
            'tools:ignore="TypographyEllipsis">Ours <b>bold</b> \\\'q\\\' %1$s</string>',
            text,
        )

    def test_missing_stages(self):
        self.assertEqual(
            merge_strings.merge_texts(None, None, THEIRS, True).text, THEIRS
        )
        self.assertEqual(
            merge_strings.merge_texts(None, OURS, None, True).text, OURS
        )
        added = merge_strings.merge_texts(None, OURS, THEIRS, True)
        self.assertIn('<string name="both_added_diff">Ours add</string>', added.text)
        self.assertIn('<string name="theirs_only">Upstream only</string>', added.text)
        self.assertIn('<string name="ours_only">Fork only</string>', added.text)

    def test_self_closing_and_attribute_parsing(self):
        text = '<resources><string name="one" translatable="false" /></resources>'
        resources = merge_strings.parse_resources(text)
        self.assertEqual(len(resources), 1)
        self.assertEqual(resources[0].name, "one")
        self.assertEqual(resources[0].block, '<string name="one" translatable="false" />')


class DuplicateGuardTest(unittest.TestCase):
    def test_find_and_remove_duplicates(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            merged = folder / "strings.xml"
            donor = folder / "strings_ko_fork.xml"
            other = folder / "strings_extra.xml"
            merged_text = (
                "<resources>"
                '<string name="shared">Merged</string>'
                '<string name="only_merged">Only</string>'
                "</resources>"
            )
            donor_text = (
                "<resources>"
                '<string name="shared">Donor</string>'
                '<string name="donor_only">Donor</string>'
                "</resources>"
            )
            other_text = (
                "<resources>"
                '<string name="shared">Other</string>'
                '<string name="other_only">Other</string>'
                "</resources>"
            )
            merged.write_text(merged_text, encoding="utf-8")
            donor.write_text(donor_text, encoding="utf-8")
            other.write_text(other_text, encoding="utf-8")

            duplicates = merge_strings.find_duplicates(merged, merged_text)
            self.assertEqual(
                {(key, path.name) for key, path in duplicates},
                {
                    (("string", "shared"), "strings_ko_fork.xml"),
                    (("string", "shared"), "strings_extra.xml"),
                },
            )

            fixed = merge_strings.remove_resources_by_keys(
                donor_text, [("string", "shared")]
            )
            self.assertNotIn('name="shared"', fixed)
            self.assertIn('<string name="donor_only">Donor</string>', fixed)

    def test_merged_file_is_not_its_own_duplicate(self):
        with tempfile.TemporaryDirectory() as tmp:
            merged = Path(tmp) / "strings.xml"
            text = '<resources><string name="shared">Merged</string></resources>'
            merged.write_text(text, encoding="utf-8")
            self.assertEqual(merge_strings.find_duplicates(merged, text), [])

    def test_distinct_tags_with_same_name_are_not_duplicates(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            merged = folder / "strings.xml"
            other = folder / "strings_extra.xml"
            merged_text = '<resources><string name="shared">Merged</string></resources>'
            other_text = (
                "<resources>"
                '<plurals name="shared"><item quantity="other">Other</item></plurals>'
                "</resources>"
            )
            merged.write_text(merged_text, encoding="utf-8")
            other.write_text(other_text, encoding="utf-8")
            self.assertEqual(
                merge_strings.find_duplicates(merged, merged_text), []
            )

            updated, _, fixed, other_writes = merge_strings.process_duplicates(
                [(merged, merged_text)], fix=True
            )
            self.assertEqual(fixed, [])
            self.assertEqual(other_writes, {})
            self.assertIn('name="shared"', updated[merged.resolve()])

    def test_fix_dupes_runs_after_all_files_are_merged(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            canonical = folder / "strings.xml"
            fork = folder / "strings_ko_fork.xml"
            canonical_text = (
                "<resources>"
                '<string name="shared">Canonical</string>'
                "</resources>"
            )
            fork_text = (
                "<resources>"
                '<string name="shared">Fork</string>'
                '<string name="fork_only">Fork only</string>'
                "</resources>"
            )
            canonical.write_text(canonical_text, encoding="utf-8")
            fork.write_text(fork_text, encoding="utf-8")

            updated, duplicates, fixed, _ = merge_strings.process_duplicates(
                [(canonical, canonical_text), (fork, fork_text)], fix=True
            )
            self.assertIn('name="shared"', updated[canonical.resolve()])
            self.assertNotIn('name="shared"', updated[fork.resolve()])
            self.assertIn('name="fork_only"', updated[fork.resolve()])
            self.assertIn((("string", "shared"), fork.resolve()), fixed)
            self.assertTrue(
                any(
                    key == ("string", "shared")
                    for key, _ in duplicates[canonical.resolve()]
                )
            )

    def test_non_canonical_merged_target_loses_to_strings_xml(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            canonical = folder / "strings.xml"
            fork = folder / "strings_ko_fork.xml"
            canonical_text = (
                "<resources>"
                '<string name="shared">Canonical</string>'
                "</resources>"
            )
            fork_text = (
                "<resources>"
                '<string name="shared">Fork</string>'
                "</resources>"
            )
            canonical.write_text(canonical_text, encoding="utf-8")
            fork.write_text(fork_text, encoding="utf-8")

            updated, _, fixed, other_writes = merge_strings.process_duplicates(
                [(fork, fork_text)], fix=True
            )
            self.assertNotIn('name="shared"', updated[fork.resolve()])
            self.assertEqual(other_writes, {})
            self.assertEqual(
                fixed, [(("string", "shared"), fork.resolve())]
            )


if __name__ == "__main__":
    unittest.main()
