# How far off are the lyrics as actually drawn? Everything else in this repository
# trusts the app's own numbers for what the screen shows; this watches the pixels.
#
# Records the phone while a song plays and puts every frame on the song's position:
#
#   frame -> elapsedRealtime (screenrecord's own per-frame stamps in the MP4)
#         -> position (the media session's PlaybackState: position, updated=elapsedRealtime)
#
# Then, for each displayed line, finds the frame where its first letter lights:
#
#   draw_lag = position at that frame - the line's start in sync-dump
#
# A sweep starts at the line's first word, which a sheet may stamp later than the line,
# so only the tightest cluster is the lag; the rest are counted as "first word later".
# The panel starts moving before the line on purpose (scrollLead, 350-500 ms): that is
# reported on its own, and is not what draw_lag measures. Lines that never go dark
# before lighting (an overlap with the line before) are skipped and counted.
#
#   heard error = sheet error (synctest.py --dry-run --out <dir>)
#               + draw_lag
#               - (real output delay - the delay AudioTrack.getTimestamp already reports)
#
# The last term needs an ear or a microphone: --echo reads it from a screen recording
# that mixed the media sound with the phone's microphone, an earbud held on the mic.
#
# Numbers and ids only: lyric text stays in the local log file and is never printed.
# Usage (with the phone connected; the flag is read when a song starts, so let this
# start the song; --no-start needs the flag already there before the song began):
#   tools\python\python.exe -I scripts\sync_screen.py --video <id> --seconds 150 [--tap X,Y ...]
#   tools\python\python.exe -I scripts\sync_screen.py --score-only <dir>
#   tools\python\python.exe -I scripts\sync_screen.py --echo <recording.mp4>
#   tools\python\python.exe -I scripts\sync_screen.py --selftest
import argparse, json, operator, random, re, statistics, struct, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]  # Desktop\BitChord
ADB = str(ROOT / "tools" / "android-sdk" / "platform-tools" / "adb.exe")
FFMPEG = str(ROOT / "tools" / "bin" / "ffmpeg.exe")
FFPROBE = str(ROOT / "tools" / "bin" / "ffprobe.exe")
PKG = "com.music.bitchord"
APP_DIR = f"/sdcard/Android/data/{PKG}/files"
REMOTE = "/sdcard/sync-screen.mp4"
MAX_RECORD_S = 180      # screenrecord refuses a longer --time-limit
SERIAL = None  # the phone every adb call is aimed at; see pick_serial

# Decoding beside FC Online and the macro: one thread, below normal.
QUIET = dict(creationflags=getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0))

CROP = "crop=iw:ih*0.62:0:ih*0.16"  # the lyric area on a phone in portrait
W, H = 360, 300         # frames as counted: enough for a line of text to be hundreds of pixels
ROWS = 300              # the row profile a scroll is read from
MAX_SHIFT = 14          # rows one frame of scroll can move at this scale
THRESHOLDS = (240, 225, 210, 195, 180, 165, 150)  # lit letters are the brightest thing on screen
MIN_LIT_PIXELS = 300    # a threshold is usable if a lit line reaches this many pixels

WINDOW_BEFORE_MS = 300  # a line's onset is looked for from this long before its stamp...
WINDOW_AFTER_MS = 1500  # ...to this long after, or the next line's stamp
DARK_RUN_MS = 60        # how long the area must be dark before a lighting counts
CLUSTER_MS = 60         # around the low end of the offsets: the lag proper
RISE_FRAMES = 6         # a lighting keeps rising this many frames later

SESSION_RE = re.compile(
    # Newer Android prints state=PLAYING(3), older state=3.
    r"state=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)\)?, position=(-?\d+).*?speed=([\d.]+), updated=(\d+)", re.S)
LOG_TIME_RE = re.compile(r"(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})")
WINSCOPE_V2 = b"#VV1NSC0PET1ME2#"
WINSCOPE_V1 = b"#VV1NSC0PET1ME!#"


def pick_serial(devices_text):
    """The USB phone's serial from `adb devices`; a wireless entry beside it must not make adb refuse."""
    ready = [p[0] for p in (l.split() for l in devices_text.splitlines()[1:]) if len(p) >= 2 and p[1] == "device"]
    usb = [s for s in ready if ":" not in s and "_adb-tls-connect" not in s]
    if len(usb) == 1:
        return usb[0]
    return ready[0] if len(ready) == 1 else None


def adb(*args, timeout=60):
    r = subprocess.run([ADB, *(["-s", SERIAL] if SERIAL else []), *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace")


def uptime_ms():
    return float(adb("shell", "cat", "/proc/uptime").split()[0]) * 1000


def parse_session(text):
    """(position ms, the elapsedRealtime ms it was true at, speed) of the app's playing session, or None."""
    for chunk in text.split("package=")[1:]:
        if not chunk.startswith(PKG):
            continue
        m = SESSION_RE.search(chunk)
        if m and m.group(1) == "3":
            return int(m.group(2)), int(m.group(4)), float(m.group(3))
    return None


def session_position():
    return parse_session(adb("shell", "dumpsys", "media_session"))


def position_at(anchors, elapsed):
    """The song's position at device [elapsed] ms, by the session's nearest report."""
    position, updated, speed = min(anchors, key=lambda a: abs(a[1] - elapsed))
    return position + (elapsed - updated) * speed


def anchor_spread(anchors):
    """How far the session's reports disagree with one straight line, ms: a seek or a swap shows here."""
    offsets = [position - updated * speed for position, updated, speed in anchors]
    return max(offsets) - min(offsets) if offsets else 0


def log_time_ms(text):
    """A logcat stamp as ms; months counted as 31 days, since only differences are used."""
    m = LOG_TIME_RE.search(text)
    if not m:
        return None
    mo, d, h, mi, s, ms = (int(x) for x in m.groups())
    return ((mo * 31 + d) * 24 + h) * 3600000 + (mi * 60 + s) * 1000 + ms


def device_clock():
    """(wall ms as the log stamps it, elapsedRealtime ms), read together on the device."""
    text = adb("shell", "date '+%m-%d %H:%M:%S.%N'; cat /proc/uptime")
    lines = text.split("\n")
    wall = log_time_ms(lines[0][:18]) if lines else None
    try:
        return wall, float(lines[1].split()[0]) * 1000
    except (IndexError, ValueError):
        return None, None


def dump_blocks(log_text, vid):
    """Every sync-dump block for [vid], oldest first: (log time ms or None, [line starts]). Never the text."""
    blocks, current = [], None
    begin, line = f"sync-dump: begin {vid} ", re.compile(rf"sync-dump: line {re.escape(vid)} \d+ (\d+) ")
    for raw in log_text.splitlines():
        if begin in raw:
            current = []
            blocks.append((log_time_ms(raw), current))
        elif current is not None:
            m = line.search(raw)
            if m:
                current.append(int(m.group(1)))
    return [b for b in blocks if b[1]]


def blocks_in_recording(blocks, first_ms, last_ms, wall_minus_elapsed):
    """The blocks that were on screen during the recording: (from elapsed ms, to elapsed ms, starts)."""
    if wall_minus_elapsed is None or any(b[0] is None for b in blocks):
        return [(first_ms, last_ms, blocks[-1][1])] if blocks else []  # the last block, for lack of a clock
    out = []
    for i, (at, starts) in enumerate(blocks):
        begin = at - wall_minus_elapsed
        end = blocks[i + 1][0] - wall_minus_elapsed if i + 1 < len(blocks) else last_ms
        if end > first_ms and begin < last_ms:
            out.append((max(begin, first_ms), min(end, last_ms), starts))
    return out


def winscope_times(data):
    """screenrecord's per-frame elapsedRealtime stamps in ms, from the MP4's metadata track, or None."""
    at = data.find(WINSCOPE_V2)
    try:
        if at >= 0:
            count = struct.unpack_from("<I", data, at + 28)[0]
            times = [t / 1e6 for t in struct.unpack_from(f"<{count}Q", data, at + 32)]
        else:
            at = data.find(WINSCOPE_V1)
            if at < 0:
                return None
            count = struct.unpack_from("<I", data, at + 16)[0]
            times = [t / 1e3 for t in struct.unpack_from(f"<{count}Q", data, at + 20)]
    except struct.error:
        return None
    if not times or any(b < a for a, b in zip(times, times[1:])):
        return None
    return times


def frame_pts(video):
    r = subprocess.run([FFPROBE, "-v", "error", "-select_streams", "v", "-show_entries", "frame=pts_time",
                        "-of", "csv=p=0", str(video)], capture_output=True, timeout=600, **QUIET)
    return [float(x.strip(",")) for x in r.stdout.decode().split() if x.strip(",")]


def raw_frames(video, vf, size):
    """Every decoded frame through [vf], one bytes object of [size] at a time, without holding them all."""
    p = subprocess.Popen([FFMPEG, "-v", "error", "-threads", "1", "-i", str(video), "-vf", vf, "-vsync", "0",
                          "-an", "-f", "rawvideo", "-"], stdout=subprocess.PIPE, **QUIET)
    while True:
        frame = p.stdout.read(size)
        if len(frame) < size:
            break
        yield frame
    p.wait(timeout=60)


def bright_counts(video):
    """Per frame, how many pixels of the lyric area reach each of [THRESHOLDS]."""
    tables = [bytes(1 if i >= t else 0 for i in range(256)) for t in THRESHOLDS]
    series = [[] for _ in THRESHOLDS]
    for frame in raw_frames(video, f"{CROP},scale={W}:{H}:flags=bilinear,format=gray", W * H):
        for table, counts in zip(tables, series):
            counts.append(frame.translate(table).count(1))
    return series


def usable_series(series):
    """The thresholds a lit line actually reaches, lowest first: (threshold, counts)."""
    usable = [(threshold, counts) for threshold, counts in zip(THRESHOLDS, series)
              if counts and sorted(counts)[int(len(counts) * 0.95)] >= MIN_LIT_PIXELS]
    return sorted(usable, key=lambda pair: pair[0])


def headline_threshold(results):
    """Of {threshold: (offsets, skipped)}, the lowest that still sees most lines go dark first.

    A lit letter's soft edge crosses a low threshold first, so a low one is the earliest the
    line can be seen to light (tens of ms ahead of a high one, measured on a real recording);
    too low, and the dimmed lines cross it too and nothing is ever dark.
    """
    for threshold in sorted(results):
        offsets, skipped = results[threshold]
        if offsets and len(offsets) >= skipped:
            return threshold
    return None


def row_shifts(video):
    """Per frame, how many rows the picture moved since the last: a sweep changes brightness, not rows."""
    out, previous = [0], None
    for frame in raw_frames(video, f"{CROP},scale=1:{ROWS}:flags=area,format=gray", ROWS):
        if previous is not None:
            out.append(row_shift(previous, frame))
        previous = frame
    return out


def row_shift(a, b):
    best, shift = None, 0
    for s in range(-MAX_SHIFT, MAX_SHIFT + 1):
        lo, hi = max(0, s), min(ROWS, ROWS + s)
        total = sum((a[i - s] - b[i]) ** 2 for i in range(lo, hi, 2)) / (hi - lo)
        if best is None or total < best:
            best, shift = total, s
    return shift


def lighting_onset(positions, counts, floor, dark, start, end):
    """The first frame in [start, end] where the area, dark for a while, starts lighting; None without a dark run."""
    dark_since = None
    for i, position in enumerate(positions):
        if position < start:
            continue
        if position > end:
            return None
        if counts[i] <= dark:
            if dark_since is None:
                dark_since = position
        else:
            later = counts[min(i + RISE_FRAMES, len(counts) - 1)]
            if dark_since is not None and position - dark_since >= DARK_RUN_MS and later > counts[i]:
                # Back to the first frame with anything lit: [dark] is a margin for noise,
                # and a sweep takes tens of ms to climb past it.
                while i > 0 and counts[i - 1] > floor + 3:
                    i -= 1
                return i
            dark_since = None
    return None


def draw_offsets(positions, counts, starts):
    """(offset ms of each line that lit cleanly, lines skipped for never going dark first)."""
    floor = min(counts)
    dark = floor + max(15, 0.02 * sorted(counts)[int(len(counts) * 0.95)])
    out, skipped = [], 0
    first, last = min(positions), max(positions)
    for i, start in enumerate(starts):
        if start < first + WINDOW_BEFORE_MS or start > last - WINDOW_AFTER_MS:
            continue
        end = start + WINDOW_AFTER_MS
        if i + 1 < len(starts):
            end = min(end, starts[i + 1])
        at = lighting_onset(positions, counts, floor, dark, start - WINDOW_BEFORE_MS, end)
        if at is None:
            skipped += 1
        else:
            out.append(positions[at] - start)
    return out, skipped


def cluster(offsets):
    """(the tight low cluster, the lines later than it): a late first word only ever adds."""
    ordered = sorted(offsets)
    low = ordered[int(len(ordered) * 0.2)]
    tight = [x for x in ordered if abs(x - low) <= CLUSTER_MS]
    return tight, [x for x in ordered if x > low + CLUSTER_MS]


def scroll_runs(positions, shifts):
    """The position each scroll of the panel starts at."""
    starts, last = [], None
    for position, shift in zip(positions, shifts):
        if shift:
            if last is None or position - last >= 150:
                starts.append(position)
            last = position
    return starts


def scroll_leads(runs, line_starts):
    """How long before the line it was heading for each scroll set off, ms."""
    out = []
    for at in runs:
        ahead = [s - at for s in line_starts if -200 <= s - at <= 1600]
        if ahead:
            out.append(min(ahead))
    return out


def frame_times(out, meta, pts):
    """Each frame's elapsedRealtime ms and where it came from."""
    stamps = winscope_times((out / "screen.mp4").read_bytes())
    if stamps:
        # The two are the same timestamps written twice; a disagreement means the stamps
        # are not what they are taken to be, and a wrong clock is worse than a stated guess.
        span_gap = abs((stamps[-1] - stamps[0]) - (pts[-1] - pts[0]) * 1000)
        near_end = "end_elapsed_ms" not in meta or abs(stamps[-1] - meta["end_elapsed_ms"]) < 60000
        if span_gap < 100 and near_end:
            return [stamps[0] + (p - pts[0]) * 1000 for p in pts], "screenrecord stamps"
        print(f"WARNING: screenrecord stamps ignored (span gap {span_gap:.0f} ms, near end {near_end})")
    # Timed by the process ending, which is a muxer flush and an adb round trip later
    # than the last frame: hundreds of ms, the size of what is being measured.
    print("WARNING: no frame stamps: the absolute draw lag below is NOT reliable, only its spread is")
    end = meta["end_elapsed_ms"]
    return [end - (pts[-1] - p) * 1000 for p in pts], "recording end (unreliable)"


def score(out):
    meta = json.loads((out / "meta.json").read_text(encoding="utf-8"))
    vid, anchors = meta["video"], [tuple(a) for a in meta["anchors"]]
    video = out / "screen.mp4"
    pts = frame_pts(video)
    if not pts:
        print("no frames read from the recording")
        return None
    series = usable_series(bright_counts(video))
    if not series:
        print("no lit line found in the recording: were the lyrics open, on a dark enough background?")
        return None
    times, clock = frame_times(out, meta, pts)
    frames = min(len(times), len(series[0][1]))
    times = times[:frames]
    shifts = row_shifts(video)[:frames]

    text = (out / f"{vid}.log").read_text(encoding="utf-8", errors="replace")
    wall = meta.get("wall_minus_elapsed_ms")
    segments = blocks_in_recording(dump_blocks(text, vid), times[0], times[-1], wall)
    if not segments:
        print(f"no sync-dump block for {vid} covers the recording: the flag was off when the song started?")
        return None

    results, leads = {threshold: ([], 0) for threshold, _ in series}, []
    for begin, end, starts in segments:
        keep = [i for i, t in enumerate(times) if begin <= t <= end]
        if len(keep) < 30:
            continue
        positions = [position_at(anchors, times[i]) for i in keep]
        for threshold, counts in series:
            got, missed = draw_offsets(positions, [counts[i] for i in keep], starts)
            results[threshold] = (results[threshold][0] + got, results[threshold][1] + missed)
        leads += scroll_leads(scroll_runs(positions, [shifts[i] for i in keep]), starts)
    dts = sorted(b - a for a, b in zip(times, times[1:]))
    row = dict(video=vid, frame_clock=clock, frames=frames, sheets_on_screen=len(segments),
               anchors=len(anchors), anchor_spread_ms=round(anchor_spread(anchors)),
               frame_ms_p50=round(dts[len(dts) // 2], 1), frame_ms_p99=round(dts[int(len(dts) * .99)], 1))
    threshold = headline_threshold(results)
    if threshold is not None:
        offsets, skipped = results[threshold]
        tight, late = cluster(offsets)
        row.update(lit_threshold=threshold, lines_lit=len(offsets), lines_skipped_overlap=skipped,
                   draw_lag_ms=round(statistics.median(tight)),
                   draw_lag_range_ms=[round(min(tight)), round(max(tight))], lines_in_lag=len(tight),
                   first_word_later_lines=len(late),
                   first_word_later_ms=round(statistics.median(late)) if late else 0,
                   lag_by_threshold={t: round(statistics.median(cluster(r[0])[0]))
                                     for t, r in sorted(results.items()) if r[0]})
    if leads:
        row.update(scroll_lead_ms=round(statistics.median(leads)),
                   scroll_held_lines=sum(1 for x in leads if x < 300), scroll_runs=len(leads))
    if text:
        clocks = [int(m.group(1)) for m in re.finditer(r"lyric-clock: .*gap=(-?\d+)ms", text)]
        row.update(lyric_clock_lines=len(clocks), lyric_clock_gap_max_ms=max((abs(c) for c in clocks), default=0),
                   spurious_timestamps=text.count("Spurious audio timestamp"))
    print(json.dumps(row))
    (out / "result.json").write_text(json.dumps(row), encoding="utf-8")
    return row


ROUTE_DUMPS = (("audio_flinger", "media.audio_flinger"), ("media_metrics", "media.metrics"), ("bluetooth", "bluetooth_manager"))
ROUTE_LINES = re.compile(r"(?i)latency|delay|codec|ldac|a2dp|output devices|thread name|sample rate|timestamp corrected|timestamp stats")


def route_snapshot(out):
    """What the system believes about the output right now; kept to the lines about timing and codec."""
    for name, service in ROUTE_DUMPS:
        kept = [l.rstrip() for l in adb("shell", "dumpsys", service, timeout=90).splitlines() if ROUTE_LINES.search(l)]
        (out / f"route-{name}.txt").write_text("\n".join(kept), encoding="utf-8")
        print(f"route {name}: {len(kept)} lines kept")


def echo_delay(samples, rate, min_ms=30, max_ms=800):
    """(lag ms, strength) of the sharpest repeat of [samples] in itself, best first."""
    diff = [b - a for a, b in zip(samples, samples[1:])]  # music is mostly low; a repeat is sharp in the rest
    energy = sum(x * x for x in diff) or 1.0
    scores = []
    for lag in range(int(min_ms * rate / 1000), int(max_ms * rate / 1000)):
        scores.append((sum(map(operator.mul, diff, diff[lag:])) / energy, lag * 1000 / rate))
    # Prominence over the neighbours: a beat's repeat is broad, an echo is a few samples wide.
    out = []
    for i in range(10, len(scores) - 10):
        near = [abs(s[0]) for s in scores[i - 10:i - 2] + scores[i + 3:i + 11]]
        out.append((scores[i][0] / (statistics.median(near) or 1e-9), scores[i][1], scores[i][0]))
    return sorted(out, reverse=True)[:3]


def echo(path):
    rate = 2000
    r = subprocess.run([FFMPEG, "-v", "error", "-threads", "1", "-i", str(path), "-vn", "-ac", "1", "-af",
                        f"highpass=f=150,aresample={rate}", "-t", "40", "-f", "s16le", "-"],
                       capture_output=True, timeout=300, **QUIET)
    count = len(r.stdout) // 2
    samples = struct.unpack(f"<{count}h", r.stdout[:count * 2])
    print(f"{count / rate:.0f} s of sound")
    for prominence, lag, value in echo_delay(samples, rate):
        print(f"repeat at {lag:.1f} ms, {prominence:.1f}x its neighbours, correlation {value:.3f}")
    print("a repeat well above the rest is the time from the phone's mix to the earbud's sound")


def selftest():
    # state=PLAYING(3) is what current Android prints; the old form must keep working.
    dump = (f"  x\n    package=other\n    state=PlaybackState {{state=3, position=1, buffered position=0, speed=1.0, updated=2, actions=0}}\n"
            f"  y\n    package={PKG}\n    active=false\n    state=PlaybackState {{state=2, position=7, buffered position=0, speed=1.0, updated=8, actions=0}}\n"
            f"  z\n    package={PKG}\n    active=true\n    state=PlaybackState {{state=PLAYING(3), position=12345, buffered position=9, speed=1.25, updated=678, actions=0}}\n")
    assert parse_session(dump) == (12345, 678, 1.25), parse_session(dump)
    assert parse_session(dump.replace("PLAYING(3)", "3")) == (12345, 678, 1.25)
    assert parse_session(dump.replace("PLAYING(3)", "PAUSED(2)")) is None

    devices = "List of devices attached\nR5CT30ABCDE\tdevice\n192.168.137.157:41234\tdevice\n"
    assert pick_serial(devices) == "R5CT30ABCDE"
    assert pick_serial("List of devices attached\n192.168.137.157:41234\tdevice\n") == "192.168.137.157:41234"
    assert pick_serial("List of devices attached\nA\tdevice\nB\tdevice\n") is None
    assert pick_serial("List of devices attached\n") is None

    anchors = [(10000, 100000, 1.0), (40000, 130000, 1.0)]
    assert position_at(anchors, 101000) == 11000
    assert position_at(anchors, 131000) == 41000
    assert anchor_spread(anchors) == 0 and anchor_spread(anchors + [(40000, 131000, 1.0)]) == 1000

    assert log_time_ms("2026-11-06 19:28:01.429 I/x") == log_time_ms("11-06 19:28:01.429  12  12 I x")
    assert log_time_ms("11-06 19:28:02.000") - log_time_ms("11-06 19:28:01.429") == 571

    log = ("11-06 10:00:00.000 x sync-dump: begin abcdefghijk p\nx sync-dump: line abcdefghijk 1 1500 rep=1/1 z\n"
           "x sync-dump: line abcdefghijk 2 3000 z\nx sync-dump: end abcdefghijk\n"
           "11-06 10:01:00.000 x sync-dump: begin abcdefghijk p\nx sync-dump: line abcdefghijk 1 900 rep=1/1 z\n")
    blocks = dump_blocks(log, "abcdefghijk")
    assert [b[1] for b in blocks] == [[1500, 3000], [900]]
    wall = log_time_ms("11-06 10:00:00.000")
    both = blocks_in_recording(blocks, 50000, 70000, wall)
    assert [(s[0], s[1], s[2]) for s in both] == [(50000, 60000, [1500, 3000]), (60000, 70000, [900])], both
    assert blocks_in_recording(blocks, 61000, 70000, wall) == [(61000, 70000, [900])]
    assert blocks_in_recording(blocks, 61000, 70000, None) == [(61000, 70000, [900])]

    stamps = (1000000000, 1016000000, 1033000000)
    v2 = WINSCOPE_V2 + struct.pack("<IqI", 2, 5, 3) + struct.pack("<3Q", *stamps)
    assert winscope_times(b"mdat" + v2 + b"more") == [1000.0, 1016.0, 1033.0]
    v1 = WINSCOPE_V1 + struct.pack("<I", 3) + struct.pack("<3Q", 1000000, 1016000, 1033000)
    assert winscope_times(v1) == [1000.0, 1016.0, 1033.0]
    assert winscope_times(b"nothing here") is None

    # Sixty frames a second; lines at 2 s and 4 s, each lighting 30 ms after its stamp, the
    # second dark for only part of its run-up; a line at 6 s that overlaps the one before.
    positions = [i * 1000 / 60 for i in range(60 * 8)]
    def lit(p):
        if 2030 <= p < 3400:
            return 2000 * (p - 2030) / 1370 + 20
        if 4030 <= p < 5600:
            return 1500 + 0 * p + (p - 4030)
        if 5600 <= p < 7500:
            return 2500 + (p - 5600) * .1
        return 0
    counts = [int(lit(p)) for p in positions]
    got, skipped = draw_offsets(positions, counts, [2000, 4000, 6000])
    assert skipped == 1, (got, skipped)
    assert all(25 <= x <= 50 for x in got) and len(got) == 2, got

    assert cluster([30, 35, 40, 500, 900]) == ([30, 35, 40], [500, 900])
    assert headline_threshold({150: ([], 9), 180: ([1], 5), 210: ([1, 2, 3], 1), 225: ([1], 0)}) == 210
    assert headline_threshold({150: ([], 9)}) is None
    assert [t for t, _ in usable_series([[0] * 10] * 2 + [[500] * 10] * 5)] == [150, 165, 180, 195, 210]

    assert scroll_runs([0, 16, 32, 48, 400, 416], [0, -3, -4, 0, -2, -2]) == [16, 400]
    assert scroll_leads([1500, 3600], [2000, 4000, 9000]) == [500, 400]

    frame_a = bytes((i * 7) % 251 for i in range(ROWS))
    frame_b = bytes(frame_a[max(0, i + 5) % ROWS] for i in range(ROWS))
    assert row_shift(frame_a, frame_b) == -5, row_shift(frame_a, frame_b)

    rng = random.Random(1)
    rate, lag = 2000, 420
    dry = [rng.gauss(0, 1000) for _ in range(rate * 6)]
    wet = [dry[i] + (0.4 * dry[i - lag] if i >= lag else 0) for i in range(len(dry))]
    best = echo_delay(wet, rate)[0]
    assert abs(best[1] - 210) < 1.0, best
    print("ok")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video")
    ap.add_argument("--seconds", type=int, default=150)
    ap.add_argument("--tap", action="append", default=[], help="X,Y to tap once playing, to open the lyrics")
    ap.add_argument("--settle", type=int, default=8, help="seconds of play before the recording starts")
    ap.add_argument("--no-start", action="store_true", help="the song is already playing with the lyrics open")
    ap.add_argument("--score-only", type=Path)
    ap.add_argument("--echo", type=Path)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if args.echo:
        return echo(args.echo)
    if args.score_only:
        return score(args.score_only)
    if not args.video:
        ap.error("--video is required to record")
    global SERIAL
    SERIAL = pick_serial(adb("devices"))
    if not SERIAL:
        sys.exit("adb sees no single phone: tools\\phone.ps1 auto, and adb devices")
    if not 1 <= args.seconds <= MAX_RECORD_S:
        sys.exit(f"--seconds must be 1 to {MAX_RECORD_S}: screenrecord stops there")
    vid = args.video
    out = ROOT / "phone-out" / f"screen-{vid}-{time.strftime('%H%M')}"
    out.mkdir(parents=True, exist_ok=True)
    adb("shell", "touch", f"{APP_DIR}/sync-test.flag")
    # The log is not cleared: a block identical to the last is not written twice, so a
    # second run of the same song would find none. Blocks are told apart by time instead.
    if not args.no_start:
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
            "-d", f"https://music.youtube.com/watch?v={vid}", "-p", PKG)
        time.sleep(args.settle)
        for tap in args.tap:
            x, y = tap.split(",")
            adb("shell", "input", "tap", x, y)
            time.sleep(1.5)
    wall, elapsed = device_clock()
    route_snapshot(out)
    anchors = []
    recorder = subprocess.Popen([ADB, "-s", SERIAL, "shell", "screenrecord", "--time-limit", str(args.seconds),
                                 "--size", "720x1560", "--bit-rate", "3000000", REMOTE])
    began = time.time()
    while recorder.poll() is None and time.time() - began < args.seconds + 20:
        p = session_position()
        if p and p not in anchors:
            anchors.append(p)
        time.sleep(5)
    recorder.wait(timeout=60)
    end = uptime_ms()
    p = session_position()
    if p and p not in anchors:
        anchors.append(p)
    meta = dict(video=vid, anchors=anchors, end_elapsed_ms=end,
                wall_minus_elapsed_ms=wall - elapsed if wall is not None and elapsed is not None else None)
    (out / "meta.json").write_text(json.dumps(meta), encoding="utf-8")
    adb("pull", REMOTE, str(out / "screen.mp4"), timeout=300)
    (out / f"{vid}.log").write_bytes(subprocess.run([ADB, "logcat", "-d", "-v", "threadtime"],
                                                    capture_output=True, timeout=120).stdout)
    adb("pull", f"{APP_DIR}/synctest/{vid}.wav", str(out / f"{vid}.wav"), timeout=120)
    adb("shell", "rm", "-f", f"{APP_DIR}/sync-test.flag", REMOTE)
    if not anchors:
        print("the media session reported no playing position: nothing to place the frames with")
        return None
    score(out)
    print("then: tools\\python\\python.exe -I scripts\\synctest.py --dry-run --out", out)


if __name__ == "__main__":
    main()
