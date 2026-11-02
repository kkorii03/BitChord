#!/usr/bin/env python3
"""Merge Android string resources inside a work tree that is mid-merge.

The script is intentionally stdlib-only and works on the raw XML bytes so that
attributes, inner markup, comments and Android escapes survive unchanged.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

FORK_MARKER = "    <!-- fork-only resources kept by merge_strings.py -->\n"

_RULE_ORDER = (
    "unchanged",
    "theirs_changed",
    "ours_changed",
    "both_same",
    "both_changed_ours",
    "both_changed_theirs",
    "theirs_only_added",
    "ours_only_added",
    "removed_dropped",
    "removed_kept",
)

_SCAN_RE = re.compile(
    r"<!--.*?-->|<\s*(string-array|plurals|string)(?=[\s/>])",
    re.DOTALL | re.IGNORECASE,
)
_ATTR_RE = re.compile(
    r"""([A-Za-z_:][A-Za-z0-9_.:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')"""
)


@dataclass
class Resource:
    tag: str
    name: str
    start: int
    end: int
    block: str

    @property
    def key(self) -> Tuple[str, str]:
        return (self.tag.lower(), self.name)


@dataclass
class MergeResult:
    text: str
    counts: Dict[str, int] = field(default_factory=dict)
    both_changed: List[Tuple[str, str, str]] = field(default_factory=list)

    def add(self, rule: str) -> None:
        self.counts[rule] = self.counts.get(rule, 0) + 1

    def note_both_changed(self, tag: str, name: str, decision: str) -> None:
        self.both_changed.append((tag, name, decision))


def _tag_end(text: str, start: int) -> int:
    quote: Optional[str] = None
    i = start
    while i < len(text):
        ch = text[i]
        if quote is not None:
            if ch == quote:
                quote = None
        elif ch in ("'", '"'):
            quote = ch
        elif ch == ">":
            return i + 1
        i += 1
    return len(text)


def _resource_name(opening_tag: str) -> Optional[str]:
    match = _ATTR_RE.search(opening_tag)
    while match:
        attr = match.group(1)
        value = match.group(2) if match.group(2) is not None else match.group(3)
        if attr == "name":
            return value
        match = _ATTR_RE.search(opening_tag, match.end())
    return None


def parse_resources(text: Optional[str]) -> List[Resource]:
    """Return top-level string/plurals/string-array resources in source order."""

    if not text:
        return []

    resources: List[Resource] = []
    pos = 0
    while True:
        match = _SCAN_RE.search(text, pos)
        if match is None:
            break
        if match.group(0).startswith("<!--"):
            pos = match.end()
            continue

        tag = match.group(1)
        open_end = _tag_end(text, match.start())
        opening = text[match.start():open_end]
        if opening.rstrip().endswith("/>"):
            end = open_end
        else:
            close_re = re.compile(
                r"</\s*" + re.escape(tag) + r"\s*>", re.IGNORECASE
            )
            close_match = close_re.search(text, open_end)
            if close_match is None:
                pos = open_end
                continue
            end = close_match.end()

        name = _resource_name(opening)
        if name is not None:
            resources.append(
                Resource(
                    tag=tag,
                    name=name,
                    start=match.start(),
                    end=end,
                    block=text[match.start():end],
                )
            )
        pos = end
    return resources


def _index(resources: Sequence[Resource]) -> Dict[Tuple[str, str], Resource]:
    result: Dict[Tuple[str, str], Resource] = {}
    for resource in resources:
        result.setdefault(resource.key, resource)
    return result


def _insert_before_closing(text: str, insertion: str) -> str:
    close_index = text.rfind("</resources>")
    if close_index < 0:
        return text + insertion
    return text[:close_index] + insertion + text[close_index:]


def merge_texts(
    base: Optional[str],
    ours: Optional[str],
    theirs: Optional[str],
    is_korean: bool,
) -> MergeResult:
    """Merge one strings.xml by resource id.

    ``None`` means the file was absent at that merge stage (added/deleted on
    one side). The merge starts from theirs' order and exact wording.
    """

    if theirs is None and ours is None:
        return MergeResult(text="")
    if theirs is None:
        return MergeResult(text=ours or "")
    if ours is None:
        return MergeResult(text=theirs)

    base_resources = parse_resources(base)
    ours_resources = parse_resources(ours)
    theirs_resources = parse_resources(theirs)

    base_map = _index(base_resources)
    ours_map = _index(ours_resources)

    result = MergeResult(text="")
    pieces: List[str] = []
    cursor = 0
    theirs_keys: Set[Tuple[str, str]] = set()

    for resource in theirs_resources:
        key = resource.key
        theirs_keys.add(key)
        theirs_block = resource.block
        base_resource = base_map.get(key)
        ours_resource = ours_map.get(key)
        chosen: Optional[str] = theirs_block

        if base_resource is None:
            if ours_resource is None:
                rule = "theirs_only_added"
            else:
                ours_block = ours_resource.block
                if ours_block == theirs_block:
                    rule = "both_same"
                elif is_korean:
                    rule = "both_changed_ours"
                    chosen = ours_block
                    result.note_both_changed(resource.tag, resource.name, "ours")
                else:
                    rule = "both_changed_theirs"
                    result.note_both_changed(resource.tag, resource.name, "theirs")
        else:
            base_block = base_resource.block
            ours_block = ours_resource.block if ours_resource is not None else None
            ours_changed = ours_block != base_block
            theirs_changed = theirs_block != base_block

            if not ours_changed:
                if theirs_changed:
                    rule = "theirs_changed"
                else:
                    rule = "unchanged"
            elif not theirs_changed:
                if ours_block is None:
                    chosen = None
                else:
                    chosen = ours_block
                rule = "ours_changed"
            elif ours_block == theirs_block:
                rule = "both_same"
            else:
                result.note_both_changed(
                    resource.tag,
                    resource.name,
                    "ours" if is_korean else "theirs",
                )
                if is_korean:
                    rule = "both_changed_ours"
                    chosen = ours_block
                else:
                    rule = "both_changed_theirs"
                    chosen = theirs_block

        pieces.append(theirs[cursor:resource.start])
        if chosen is not None:
            pieces.append(chosen)
        cursor = resource.end
        result.add(rule)

    appended_blocks: List[str] = []
    for resource in ours_resources:
        key = resource.key
        if key in theirs_keys:
            continue
        ours_block = resource.block
        base_resource = base_map.get(key)
        if base_resource is None:
            rule = "ours_only_added"
            append = ours_block
        elif ours_block == base_resource.block:
            rule = "removed_dropped"
            append = None
        else:
            rule = "removed_kept"
            append = ours_block
        result.add(rule)
        if append is not None:
            appended_blocks.append("    " + append + "\n")

    tail = theirs[cursor:]
    if appended_blocks:
        insertion = "\n" + FORK_MARKER + "".join(appended_blocks)
        tail = _insert_before_closing(tail, insertion)

    pieces.append(tail)
    result.text = "".join(pieces)
    return result


def collect_names(text: Optional[str]) -> Set[str]:
    return {resource.name for resource in parse_resources(text)}


def find_duplicates(
    merged_path: Path, merged_text: str
) -> List[Tuple[str, Path]]:
    """Find names in merged_text that another XML file in the folder defines."""

    merged_names = collect_names(merged_text)
    duplicates: List[Tuple[str, Path]] = []
    folder = merged_path.parent
    if not folder.is_dir():
        return duplicates

    for other in sorted(folder.iterdir()):
        if not other.is_file() or other.suffix.lower() != ".xml":
            continue
        if other.resolve() == merged_path.resolve():
            continue
        try:
            other_text = other.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for name in sorted(merged_names & collect_names(other_text)):
            duplicates.append((name, other))
    return duplicates


def remove_resources_by_names(text: str, names: Iterable[str]) -> str:
    wanted = set(names)
    pieces: List[str] = []
    cursor = 0
    for resource in parse_resources(text):
        if resource.name in wanted:
            pieces.append(text[cursor:resource.start])
            cursor = resource.end
    pieces.append(text[cursor:])
    return "".join(pieces)


def _read_stage(path: str, stage: int) -> Optional[str]:
    process = subprocess.run(
        ["git", "show", f":{stage}:{path}"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    if process.returncode != 0:
        return None
    return process.stdout.decode("utf-8")


def _conflicted_strings_paths() -> List[str]:
    process = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=U"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    if process.returncode != 0:
        raise SystemExit(process.stderr.strip() or "git diff failed")
    pattern = re.compile(r"(?:^|/)res/values[^/]*/strings[^/]*\.xml$")
    return [line for line in process.stdout.splitlines() if pattern.search(line)]


def _is_korean_locale(path: str) -> bool:
    normalized = path.replace(os.sep, "/")
    return bool(re.search(r"(?:^|/)values-ko(?:/|$)", normalized))


def _print_report(
    path: str,
    result: MergeResult,
    duplicates: Sequence[Tuple[str, Path]],
    fixed_duplicates: Sequence[Tuple[str, Path]],
) -> None:
    print(f"== {path} ==")
    for rule in _RULE_ORDER:
        count = result.counts.get(rule, 0)
        if count:
            print(f"  {rule}: {count}")

    if result.both_changed:
        rendered = ", ".join(
            f"{tag}:{name} ({decision})"
            for tag, name, decision in result.both_changed
        )
    else:
        rendered = "(none)"
    print(f"  both-changed keys: {rendered}")

    if duplicates:
        for name, other in duplicates:
            print(f"  duplicate: {name} also in {other}")
    else:
        print("  duplicates: (none)")

    if fixed_duplicates:
        for name, other in fixed_duplicates:
            print(f"  fixed-duplicate: removed {name} from {other}")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Merge conflicted Android strings.xml files by resource id."
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the report without writing or staging files",
    )
    parser.add_argument(
        "--stage",
        action="store_true",
        help="git add merged files (and files touched by --fix-dupes)",
    )
    parser.add_argument(
        "--fix-dupes",
        action="store_true",
        help="remove duplicate names from non-strings.xml files",
    )
    parser.add_argument(
        "paths",
        nargs="*",
        help="optional conflicted strings.xml paths (defaults to git conflicts)",
    )
    args = parser.parse_args(argv)

    paths = args.paths or _conflicted_strings_paths()
    if not paths:
        print("No conflicted strings resources found.")
        return 0

    for path in paths:
        base = _read_stage(path, 1)
        ours = _read_stage(path, 2)
        theirs = _read_stage(path, 3)
        if base is None and ours is None and theirs is None:
            continue

        result = merge_texts(
            base=base,
            ours=ours,
            theirs=theirs,
            is_korean=_is_korean_locale(path),
        )
        target = Path(path)
        duplicates = find_duplicates(target, result.text)

        fixed: List[Tuple[str, Path]] = []
        if not args.dry_run:
            target.write_bytes(result.text.encode("utf-8"))
            if args.stage:
                subprocess.run(["git", "add", "--", path], check=True)
            if args.fix_dupes and duplicates:
                by_file: Dict[Path, Set[str]] = {}
                for name, other in duplicates:
                    if other.name == "strings.xml":
                        continue
                    by_file.setdefault(other, set()).add(name)
                for other, names in by_file.items():
                    text = other.read_text(encoding="utf-8")
                    other.write_bytes(
                        remove_resources_by_names(text, names).encode("utf-8")
                    )
                    if args.stage:
                        subprocess.run(
                            ["git", "add", "--", str(other)], check=True
                        )
                    for name in sorted(names):
                        fixed.append((name, other))

        _print_report(path, result, duplicates, fixed)

    return 0


if __name__ == "__main__":
    sys.exit(main())
