#!/usr/bin/env python3
"""Check ui-tour uiautomator dumps for broken Korean text.

Reads every shots/*.xml beside the screenshots (or --shots DIR) and writes a
Markdown report:

  (a) text clipped or ellipsized by its own bounds
  (b) a line break inside a Hangul word (explicit or Android's own wrap)
  (c) two text nodes drawn over each other
  (d) English UI left in the Korean screen (a string that has a values-ko
      translation is shown in its English form)
  (e) list rows in one screen whose per-line text sizes jump
  (f) a line holding only one or two characters

The checks are heuristics: they point a reviewer at the screen and the node,
they do not replace looking at the PNG. Run --self-test for the fixture check.
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

HANGUL = re.compile(r"[\uac00-\ud7a3\u1100-\u11ff\u3130-\u318f]")
LATIN_WORD = re.compile(r"[A-Za-z][A-Za-z0-9'’.-]*")
BOUNDS = re.compile(r"-?\d+")
# Latin brands and technical terms that are deliberately not translated, even
# when some resource happens to carry a Hangul translation for the same word.
PROPER_NOUNS = {
    "bitchord", "youtube", "spotify", "discord", "telegram", "last.fm",
    "listenbrainz", "webdav", "smb", "usb", "dac", "eq", "gemini", "deepgram",
    "chromecast", "google", "wear", "os", "replay", "opus", "flac", "aac",
    "mp3", "android", "ios", "h.264", "av1", "fullhd", "wifi", "wi-fi",
    "atmos", "dolby", "airplay", "siri", "canvas", "cast", "pcm", "dsd",
}
STOPWORDS = {
    "a", "an", "and", "or", "of", "to", "in", "on", "for", "the", "with",
    "from", "by", "at", "is", "are", "it", "this", "that",
}


@dataclass
class Node:
    text: str
    cls: str
    package: str
    bounds: tuple[int, int, int, int]
    parent: "Node | None" = None
    children: list["Node"] = field(default_factory=list)

    @property
    def width(self) -> int:
        return max(0, self.bounds[2] - self.bounds[0])

    @property
    def height(self) -> int:
        return max(0, self.bounds[3] - self.bounds[1])


@dataclass
class Issue:
    kind: str
    text: str
    detail: str


def _box(raw: str | None) -> tuple[int, int, int, int]:
    nums = [int(x) for x in BOUNDS.findall(raw or "")]
    if len(nums) != 4:
        return (0, 0, 0, 0)
    return tuple(nums)  # type: ignore[return-value]


def parse_ui(path: Path) -> list[Node]:
    root = ET.parse(path).getroot()
    out: list[Node] = []

    def walk(elem: ET.Element, parent: Node | None) -> None:
        node = None
        if elem.tag == "node":
            node = Node(
                text=(elem.get("text") or "").strip(),
                cls=elem.get("class") or "",
                package=elem.get("package") or "",
                bounds=_box(elem.get("bounds")),
                parent=parent,
            )
            out.append(node)
            if parent is not None:
                parent.children.append(node)
        for child in elem:
            walk(child, node or parent)

    walk(root, None)
    return out


def _char_em(ch: str) -> float:
    if HANGUL.match(ch) or "\u3000" <= ch <= "\u9fff":
        return 1.0
    if ch.isupper():
        return 0.62
    if ch.isdigit():
        return 0.56
    if ch in " .,:;!?()[]{}'\"·-—…/":
        return 0.32
    return 0.53


def _font_px(node: Node) -> float:
    lines = max(1, node.text.count("\n") + 1)
    # A text box carries a little leading/padding; 0.75 of the line box is a
    # conservative proxy for the type size without a font size in the dump.
    return max(8.0, (node.height / lines) * 0.75)


def _needed_width(line: str, font_px: float) -> float:
    return sum(_char_em(ch) for ch in line) * font_px


def _base_line_height(nodes: list[Node]) -> float:
    """Typical single-line height on this screen, as a font-size proxy.

    The dump has no text size, and a wrapped node's own height is a multiple
    of the line height: using it directly would make every wrapped paragraph
    look one line tall. Short single-line nodes give the proportion instead.
    """
    heights = sorted(
        n.height for n in nodes
        if n.text and "\n" not in n.text and 2 <= len(n.text) <= 24 and 12 <= n.height <= 200
    )
    if not heights:
        return 0.0
    return float(heights[len(heights) // 2])


def _split_word(word: str, width: int, font_px: float) -> list[str]:
    parts: list[str] = []
    while word:
        take = 0
        while take < len(word) and _needed_width(word[: take + 1], font_px) <= width:
            take += 1
        take = max(1, take)
        parts.append(word[:take])
        word = word[take:]
    return parts


def _wrap_lines(text: str, width: int, font_px: float) -> list[str]:
    """Greedy wrap at spaces, breaking an over-long word by characters."""
    if width <= 0:
        return text.split("\n")
    out: list[str] = []
    for para in text.split("\n"):
        cur = ""
        for word in para.split(" "):
            candidate = word if not cur else cur + " " + word
            if not cur:
                if _needed_width(word, font_px) <= width:
                    cur = word
                else:
                    parts = _split_word(word, width, font_px)
                    out.extend(parts[:-1])
                    cur = parts[-1]
            elif _needed_width(candidate, font_px) <= width:
                cur = candidate
            else:
                out.append(cur)
                if _needed_width(word, font_px) <= width:
                    cur = word
                else:
                    parts = _split_word(word, width, font_px)
                    out.extend(parts[:-1])
                    cur = parts[-1]
        out.append(cur)
    return out


def _visual_lines(node: Node, base: float) -> list[str]:
    """Lines the TextView actually paints: explicit breaks, else its wrap."""
    if "\n" in node.text:
        return node.text.split("\n")
    # An ellipsis is a single line cut by its box, not a wrapped paragraph.
    if "…" in node.text or node.text.rstrip().endswith("..."):
        return [node.text]
    if base <= 0:
        return [node.text]
    return _wrap_lines(node.text, node.width, base * 0.75)


def _fits_one_line(node: Node) -> bool:
    """The text on one line at the type size its own box height implies."""
    return _needed_width(node.text, _font_px(node)) <= node.width * 1.05


def _fits_wrapped(node: Node, base: float) -> bool:
    """The text wrapped at the screen's body size, within the box height."""
    if base <= 0:
        return False
    return len(_wrap_lines(node.text, node.width, base * 0.75)) * base <= node.height * 1.15


def _clip(node: Node, base: float) -> Issue | None:
    if node.width <= 0 or node.height <= 0:
        return None
    explicit = node.text.split("\n")
    font = base * 0.75 if base > 0 else _font_px(node)
    if "\n" in node.text:
        # Each explicit line fits its own line box; only its width can overflow.
        cut = any(_needed_width(line, font) > node.width * 1.05 for line in explicit)
    else:
        # The dump has no type size: a box can be one line of big text or
        # several wrapped lines of body text. Cut only when it is neither
        # (review r2: a cut big header, and no false cut on a wrapped paragraph).
        cut = not (_fits_one_line(node) or _fits_wrapped(node, base))
        font = _font_px(node)
    ellipsis = "…" in node.text or node.text.rstrip().endswith("...")
    if not cut:
        return None
    needed = max((_needed_width(line, font) for line in explicit), default=0.0)
    why = "ends in an ellipsis" if ellipsis else "needs more room than its box"
    return Issue(
        "a-cut",
        node.text,
        f"{why}; text needs ~{needed:.0f}px, box is {node.width}px x {node.height}px at {node.bounds[0]},{node.bounds[1]}",
    )


def _split_hangul(node: Node, base: float) -> Issue | None:
    if "\n" not in node.text:
        # A single word (no spaces) painted on more than one line was broken
        # between two Hangul syllables by the text layout, not by the author.
        lines = _visual_lines(node, base)
        if (
            len(lines) > 1
            # Wrapped only if it does not fit one line yet fits as wrapped body
            # text; otherwise the box is too small and that is a cut (a-cut).
            and not _fits_one_line(node)
            and _fits_wrapped(node, base)
            and " " not in node.text
            and HANGUL.search(node.text)
        ):
            return Issue(
                "b-split-word",
                node.text,
                f"wrapped over {len(lines)} lines without a word break at {node.bounds[0]},{node.bounds[1]}",
            )
        return None
    lines = node.text.split("\n")
    for before, after in zip(lines, lines[1:]):
        # A break beside a space is a word boundary, not a split.
        if not before or not after or before[-1].isspace() or after[0].isspace():
            continue
        left, right = before[-1], after[0]
        if HANGUL.match(left) and HANGUL.match(right):
            return Issue(
                "b-split-word",
                node.text,
                f"line break between '{left}' and '{right}' splits a Hangul word at {node.bounds[0]},{node.bounds[1]}",
            )
    return None


def _short_line(node: Node, base: float) -> Issue | None:
    lines = _visual_lines(node, base)
    if len(lines) < 2:
        return None
    for index, line in enumerate(lines):
        stripped = line.strip()
        if 1 <= len(stripped) <= 2 and HANGUL.search(stripped):
            return Issue(
                "f-short-line",
                node.text,
                f"line {index + 1} holds only '{stripped}' at {node.bounds[0]},{node.bounds[1]}",
            )
    return None


def _overlap(a: Node, b: Node) -> Issue | None:
    if a.text == b.text:
        return None
    x1, y1 = max(a.bounds[0], b.bounds[0]), max(a.bounds[1], b.bounds[1])
    x2, y2 = min(a.bounds[2], b.bounds[2]), min(a.bounds[3], b.bounds[3])
    if x2 - x1 < 4 or y2 - y1 < 4:
        return None
    inter = (x2 - x1) * (y2 - y1)
    smaller = min(a.width * a.height, b.width * b.height)
    if smaller <= 0 or inter / smaller < 0.3:
        return None
    return Issue(
        "c-overlap",
        f"{a.text} / {b.text}",
        f"{a.bounds} overlaps {b.bounds}",
    )


def _texts(node: Node) -> list[Node]:
    found = [node] if node.text else []
    for child in node.children:
        found.extend(_texts(child))
    return found


def _size_jump(root: list[Node], base: float) -> list[Issue]:
    issues: list[Issue] = []
    seen: set[tuple[int, int, int, int]] = set()

    def per_line_height(node: Node) -> float:
        return node.height / max(1, len(_visual_lines(node, base)))

    def visit(node: Node) -> None:
        items = []
        for child in node.children:
            texts = _texts(child)
            if texts:
                items.append((child, max(per_line_height(t) for t in texts)))
        if len(items) >= 2:
            heights = [h for _, h in items if h >= 18]
            if heights and max(heights) / min(heights) >= 1.35:
                key = node.bounds
                if key not in seen:
                    seen.add(key)
                    issues.append(
                        Issue(
                            "e-size-jump",
                            " / ".join(t.text for c, _ in items for t in _texts(c)[:1]),
                            f"row line heights {sorted(round(h) for h in heights)} in one list at {node.bounds}",
                        )
                    )
        for child in node.children:
            visit(child)

    roots = [n for n in root if n.parent is None]
    for n in roots:
        visit(n)
    return issues


def _parse_strings(path: Path) -> dict[str, str]:
    try:
        tree = ET.parse(path)
    except Exception:
        return {}
    out: dict[str, str] = {}
    for elem in tree.getroot().iter("string"):
        name = elem.get("name")
        if name:
            out[name] = "".join(elem.itertext()).strip()
    return out


def load_untranslated_terms(repo_root: Path) -> set[str]:
    """English labels that the Korean resources translate, as matchable terms."""
    pairs = [
        (repo_root / "app/src/main/res/values/strings.xml", repo_root / "app/src/main/res/values-ko/strings.xml"),
        (
            repo_root / "sharedUi/src/commonMain/composeResources/values/strings.xml",
            repo_root / "sharedUi/src/commonMain/composeResources/values-ko/strings.xml",
        ),
    ]
    terms: set[str] = set()
    for english_path, korean_path in pairs:
        english = _parse_strings(english_path)
        korean = _parse_strings(korean_path)
        for name, eng in english.items():
            kor = korean.get(name)
            if not kor or not HANGUL.search(kor) or kor.strip() == eng.strip():
                continue
            clean = re.sub(r"%\d+\$[sd]|%[sd]", "", eng)
            clean = re.sub(r"\s+", " ", clean).strip()
            if not clean:
                continue
            if len(clean.split()) == 1 and clean.lower() not in STOPWORDS:
                if LATIN_WORD.fullmatch(clean):
                    terms.add(clean.lower())
            elif len(clean.split()) >= 2:
                terms.add(clean.lower())
    return terms


def _latin_words(text: str) -> list[str]:
    return [m.group(0).lower() for m in LATIN_WORD.finditer(text)]


def _english_left(node: Node, terms: set[str]) -> Issue | None:
    low = node.text.lower()
    words = _latin_words(node.text)
    # Match whole tokens: "Broadcast settings" is not excused by "cast" in it.
    only_proper = bool(words) and all(
        word in PROPER_NOUNS or word in STOPWORDS for word in words
    )
    if low in terms and not only_proper:
        return Issue("d-english", node.text, "resource has a Korean translation")
    for match in LATIN_WORD.finditer(node.text):
        word = match.group(0).lower()
        if word in terms and word not in PROPER_NOUNS and word not in STOPWORDS:
            return Issue("d-english", node.text, f"untranslated UI word '{match.group(0)}'")
    return None


def check_screen(path: Path, terms: set[str]) -> list[Issue]:
    all_nodes = parse_ui(path)
    nodes = [n for n in all_nodes if n.text]
    if not nodes:
        return []
    base = _base_line_height(nodes)
    issues: list[Issue] = []
    for node in nodes:
        clipped = _clip(node, base)
        if clipped:
            issues.append(clipped)
        split = _split_hangul(node, base)
        if split:
            issues.append(split)
        short = _short_line(node, base)
        if short:
            issues.append(short)
        english = _english_left(node, terms)
        if english:
            issues.append(english)
    for i, a in enumerate(nodes):
        for b in nodes[i + 1 :]:
            both = _overlap(a, b)
            if both:
                issues.append(both)
    issues.extend(_size_jump(all_nodes, base))
    return issues


def screen_has_hangul(path: Path) -> bool:
    return any(HANGUL.search(n.text) for n in parse_ui(path))


def render(reports: list[tuple[str, list[Issue]]], has_hangul: bool | None = None) -> str:
    total = sum(len(i) for _, i in reports)
    if has_hangul is None:
        # Kept for callers that only have the issue list.
        has_hangul = any(page and any(issue.kind != "d-english" for issue in page) for _, page in reports)
    lines = [
        "# UI text check",
        "",
        f"Screens checked: {len(reports)} · issues: {total}",
        "",
        "Heuristics over the uiautomator dumps: (a) clipped/ellipsized text, "
        "(b) Hangul word split by a line break, (c) overlapping text, "
        "(d) English UI with a Korean translation, (e) list row size jumps, "
        "(f) a line holding only 1-2 characters.",
        "",
    ]
    if not has_hangul:
        lines += ["No Korean text found in these dumps; check the emulator locale.", ""]
    for name, issues in reports:
        if not issues:
            continue
        lines.append(f"## {name}")
        for issue in issues:
            lines.append(f"- **{issue.kind}** `{issue.text}` — {issue.detail}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def run(shots: Path, report: Path, repo_root: Path) -> int:
    terms = load_untranslated_terms(repo_root)
    screens = sorted(shots.glob("*.xml"))
    reports = [(p.name, check_screen(p, terms)) for p in screens]
    has_hangul = any(screen_has_hangul(p) for p in screens)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(render(reports, has_hangul), encoding="utf-8")
    total = sum(len(i) for _, i in reports)
    print(f"ui-textcheck: {len(screens)} screens, {total} issues -> {report}")
    return 0


FIXTURE = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
 <node text="" class="android.widget.FrameLayout" package="com.dev.bitchord" bounds="[0,0][1080,2400]">
  <node text="이것은 아주 긴 제목입니다…" class="android.widget.TextView" package="com.dev.bitchord" bounds="[10,10][120,60]"/>
  <node text="한국어&#10;단어" class="android.widget.TextView" package="com.dev.bitchord" bounds="[10,100][300,180]"/>
  <node text="겹치는 텍스트" class="android.widget.TextView" package="com.dev.bitchord" bounds="[10,220][400,300]"/>
  <node text="다른 텍스트" class="android.widget.TextView" package="com.dev.bitchord" bounds="[20,240][380,320]"/>
  <node text="Settings" class="android.widget.TextView" package="com.dev.bitchord" bounds="[10,340][400,400]"/>
  <node text="가&#10;나" class="android.widget.TextView" package="com.dev.bitchord" bounds="[10,400][400,500]"/>
  <node text="Dance Monkey" class="android.widget.TextView" package="com.dev.bitchord" bounds="[10,410][400,470]"/>
  <node text="" class="android.widget.LinearLayout" package="com.dev.bitchord" bounds="[0,500][1080,800]">
   <node text="첫 행" class="android.widget.TextView" package="com.dev.bitchord" bounds="[10,510][400,558]"/>
   <node text="둘째 행" class="android.widget.TextView" package="com.dev.bitchord" bounds="[10,580][400,652]"/>
  </node>
 </node>
</hierarchy>
"""


def self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "shots").mkdir()
        (root / "shots/01-fixture.xml").write_text(FIXTURE, encoding="utf-8")
        (root / "values").mkdir()
        (root / "values-ko").mkdir()
        (root / "values/strings.xml").write_text('<resources><string name="settings">Settings</string></resources>', encoding="utf-8")
        (root / "values-ko/strings.xml").write_text('<resources><string name="settings">설정</string></resources>', encoding="utf-8")
        # The loader reads fixed project paths; stand in a minimal repo layout.
        for part in ("app/src/main/res", "sharedUi/src/commonMain/composeResources"):
            base = root / part
            (base / "values").mkdir(parents=True)
            (base / "values-ko").mkdir(parents=True)
            (base / "values/strings.xml").write_text((root / "values/strings.xml").read_text(), encoding="utf-8")
            (base / "values-ko/strings.xml").write_text((root / "values-ko/strings.xml").read_text(), encoding="utf-8")
        terms = load_untranslated_terms(root)
        issues = check_screen(root / "shots/01-fixture.xml", terms)
        kinds = {i.kind for i in issues}
        expected = {"a-cut", "b-split-word", "c-overlap", "d-english", "e-size-jump", "f-short-line"}
        missing = expected - kinds
        if missing:
            print(f"self-test: missing {sorted(missing)} in {sorted(kinds)}", file=sys.stderr)
            return 1
    print("self-test: ok")
    return 0


def main(argv: list[str] | None = None) -> int:
    here = Path(__file__).resolve()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shots", type=Path, default=here.parents[2] / "shots")
    parser.add_argument("--report", type=Path, default=None)
    parser.add_argument("--repo-root", type=Path, default=here.parents[2])
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    report = args.report or args.shots / "ui-text-issues.md"
    return run(args.shots, report, args.repo_root)


if __name__ == "__main__":
    raise SystemExit(main())
