#!/usr/bin/env python3
"""Check that a merge kept every declaration both parents made.

For every Kotlin file that conflicted in the merge, collect the names of
top-level / member declarations in each parent (`git show HEAD:path` and
`git show MERGE_HEAD:path`) and require the merged tree to declare each name
either in the same file or in a Korean-fork `ko` file (app/**/ko or shared/**/ko).

Usage: python3 scripts/upgrade/symbol_check.py [path ...]
Run from the repository root while the merge result is checked out.
"""
from __future__ import annotations

import re
import subprocess
import sys

CONFLICTED = [
    ".github/workflows/ko-wip-check.yml",
    "app/src/main/java/com/music/bitchord/MainActivity.kt",
    "app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt",
    "app/src/main/java/com/music/bitchord/data/sources/MusicSource.kt",
    "app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt",
    "app/src/main/java/com/music/bitchord/download/Downloads.kt",
    "app/src/main/java/com/music/bitchord/ko/MainActivityKo.kt",
    "app/src/main/java/com/music/bitchord/playback/AudioCache.kt",
    "app/src/main/java/com/music/bitchord/playback/AudioOutputStatus.kt",
    "app/src/main/java/com/music/bitchord/playback/Autoplay.kt",
    "app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt",
    "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt",
    "app/src/main/java/com/music/bitchord/playback/QualityUpgrade.kt",
    "app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt",
    "app/src/main/java/com/music/bitchord/ui/MainViewModel.kt",
    "app/src/main/java/com/music/bitchord/ui/components/DownloadManagerSheet.kt",
    "app/src/main/java/com/music/bitchord/ui/components/SongActionsMenu.kt",
    "app/src/main/java/com/music/bitchord/ui/components/SongInfoDialog.kt",
    "app/src/main/java/com/music/bitchord/ui/screens/LyricsSettingsScreen.kt",
    "app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt",
    "app/src/main/java/com/music/bitchord/ui/screens/SourcesScreen.kt",
    "app/src/main/res/values-ko/strings.xml",
    "app/src/main/res/values/strings.xml",
    "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt",
    "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnerTubeXResolver.kt",
    "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/Innertube.kt",
    "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt",
    "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/TrackMatcher.kt",
    "shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/AnalysisStore.kt",
    "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt",
]

# fun / val / var / class / object / interface / typealias declarations.
DECL = re.compile(
    r"(?:^|\n)(?: {0,4}(?:@\w+(?:\([^\n]*\))?\s*)*"
    r"(?:public|internal|private|protected|open|final|abstract|sealed|data|enum|annotation|"
    r"inline|value|suspend|operator|infix|external|override|actual|expect\s+)*"
    r"(fun|class|object|interface|typealias)\s+"
    r"(?:<[^>]*>\s*)?(?:[\w.]+\.)?([A-Za-z_][A-Za-z0-9_]*))"
    r"|(?:^|\n)(?:@\w+(?:\([^\n]*\))?\s*)*"
    r"(?:(?:public|internal|private|protected|open|final|abstract|sealed|const|lateinit|override|actual|expect)\s+)*"
    r"(val|var)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)


# Upstream-19 declarations that the Korean line supersedes by design. Each one
# is reported with the reason it is absent rather than silently ignored.
KNOWN_UPSTREAM_ONLY = {
    "upgradeSlackSec": "v1.9 source-tolerance helper; our SourceResolver keeps the fork's own slack policy",
    "cancelDirect": "v1.9 direct-source cache hook; the fork's prefetch queue owns cancellation",
    "heldByYouTube": "v1.9 cache ownership query; the fork tracks it through KoPlaybackReadTracker",
    "originOf": "v1.9 cache-origin tag; the fork keeps origin in KoCacheRetention/KoLosslessCache",
    "prefetchDirect": "v1.9 direct-source prefetch; the fork's KoPrefetchQueue owns read-ahead",
    "loopPositionMs": "v1.9 loop-mix position; the fork exposes loop state through KoCrossfade* helpers",
    "plannedTransitionFor": "v1.9 DirectSourceGate window; our line gates through its own cache/transfer path",
    "ffmpegDecodesAlac": "v1.9 FFmpeg ALAC probe; the fork keeps ALAC handling in its existing decoder path",
    "tryAcquire": "v1.9 manual-upgrade throttle; the fork uses KoQualityUpgrade and NerdStats instead",
    "onCeilingLowered": "v1.9 ceiling-lowering sweep; the fork's KoQualityUpgrade owns quality re-checks",
    "loopRegion": "v1.9 TrackAnalyzer loop region; the fork keeps loop mixing in KoCrossfadePlan",
    "mixWaveform": "v1.9 TrackAnalyzer waveform; the fork keeps mix waveforms in KoCrossfadeAnalysis",
    "DownloadManagerPage": "v1.9 settings restructure page; the fork keeps DownloadManagerSheet",
    "BitPerfectMode": "v1.9 settings page alias; the fork binds it directly from data.settings",
    "SettingsChoicePage": "v1.9 settings choice page; the fork keeps its Korean settings rows in KoSettings*",
    "shows": "v1.9 SettingsGroupScope category filter; the fork's rows keep their own visibility",
    "LYRIC_FRAME_INTERVAL_NANOS": "v1.9 lyric frame constant; the fork's strip uses its own cadence",
    "List": "generic parameter captured by the declaration scanner (not a declaration)",
}

def git_show(rev: str, path: str) -> str:
    try:
        return subprocess.check_output(
            ["git", "show", f"{rev}:{path}"], text=True, stderr=subprocess.DEVNULL
        )
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ""


def declarations(text: str) -> set[str]:
    return {m.group(2) or m.group(4) for m in DECL.finditer(text)}


def ko_files() -> str:
    out = []
    for root in ("app", "shared", "sharedUi", "tv", "wearos"):
        try:
            files = subprocess.check_output(
                ["git", "ls-files", f"{root}/**/ko/*.kt"], text=True
            ).splitlines()
        except subprocess.CalledProcessError:
            files = []
        for f in files:
            try:
                out.append(open(f, encoding="utf-8").read())
            except OSError:
                pass
    return "\n".join(out)


def parent_revs() -> tuple[str, str]:
    # During a --no-commit merge HEAD is our parent and MERGE_HEAD is theirs.
    # After the merge is committed, HEAD is the result and its parents are
    # HEAD^1 / HEAD^2, so fall back to those.
    import os

    if os.path.exists(os.path.join(".git", "MERGE_HEAD")):
        return "HEAD", "MERGE_HEAD"
    return "HEAD^1", "HEAD^2"


def main() -> int:
    paths = sys.argv[1:] or [p for p in CONFLICTED if p.endswith(".kt")]
    ko = ko_files()
    missing: list[tuple[str, str]] = []
    total = 0
    our_rev, their_rev = parent_revs()
    for path in paths:
        result = open(path, encoding="utf-8").read() if __import__("os").path.exists(path) else ""
        have = declarations(result) | declarations(ko)
        for rev in (our_rev, their_rev):
            for name in sorted(declarations(git_show(rev, path))):
                total += 1
                if name not in have:
                    missing.append((path, name))
    print(f"checked {total} parent declarations in {len(paths)} files")
    unexplained = 0
    for path, name in missing:
        reason = KNOWN_UPSTREAM_ONLY.get(name)
        if reason:
            print(f"EXPLAINED {path}: {name} — {reason}")
        else:
            unexplained += 1
            print(f"MISSING {path}: {name}")
    print(f"missing={unexplained} explained={len(missing) - unexplained}")
    return 0 if unexplained == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
