"""Press lead time vs the game's verdict, PRACTICE sessions only (release clock = boxstep.py's slot-2 brightness step).

presswin.py <game folder> [<game folder> ...] [-from s] [-to s] [-csv out.csv]

For every release step: every block-button press in the 1.2 s before it and 0.3 s after it, as "lead" = ms from the
press to the release (+ = pressed before the ball left), who pressed (app = our injected tap, hand = the user's thumb),
and whether the game then drew the success word '블록' (count_block_labels.scan_clip on the clip of that press:
"BLOCK", "fail" = the word with more text after it (위치 부족 / 타이밍 늦음 / 타이밍 빠름), "none" = no '블록' word at all
(강한 컨테스트, 컨테스트 실패 or nothing), "noclip" = that press has no clip in the bundle).
Press time: app = tap_dispatch in <session>_ingameproxy (the tap going down), else the `pressed` row of shot_events.csv;
hand = presses.csv (the button's brightness flash) that is not within 0.12 s of an app tap.
"screen" = ms from the FIRST visible change of the block button in that clip to the release, and "early" when that change
came more than 60 ms before our tap: a finger pressed first (such presses are in no log: 7 of 51 on 11-10/11-11, all
the "late tap but still a block" rows, HANDOFF 0-4z7). Our own 33 ms tap is a one-frame dip and is sometimes not seen
(screen then shows a later change, negative or about -1300): trust "early" rows, not the screen number of "same" rows.
The last blocks print the leads per outcome. Do not use on match sessions (the step is not a release clock there).
"""
import sys, os, csv, re, glob
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import cv2
import count_block_labels as cbl

a = sys.argv[1:]
def opt(k, d): return float(a[a.index(k) + 1]) if k in a else d
T0, T1 = opt("-from", 0.0), opt("-to", 1e9)
OUT = a[a.index("-csv") + 1] if "-csv" in a else None
dirs = []
i = 0
while i < len(a):
    if a[i] in ("-from", "-to", "-csv"): i += 2
    else: dirs.append(a[i]); i += 1

base = cv2.imread(cbl.TPL, cv2.IMREAD_GRAYSCALE)
TPLS = [cv2.resize(base, None, fx=640 / 2340.0 * k, fy=640 / 2340.0 * k, interpolation=cv2.INTER_AREA) for k in (0.8, 0.9, 1.0, 1.12, 1.25)]
allrows = []
for g in dirs:
    g = g.rstrip("\\/")
    rows = list(csv.DictReader(open(os.path.join(g, "shot.csv"))))
    t0ns = int(rows[0]["t_ns"]) - float(rows[0]["t_s"]) * 1e9
    R = [(float(r["t_s"]), float(r["slot2_luma"])) for r in rows]
    steps = []
    for i in range(16, len(R)):
        b = [q[1] for q in R[i - 15:i - 2]]
        if max(b) - min(b) >= 1.5 or abs(R[i - 1][1] - b[-1]) >= 5: continue
        d = R[i][1] - b[-1]
        if 6 <= d <= 12 and 98 <= b[-1] <= 112 and (not steps or R[i][0] - steps[-1] > 0.3): steps.append(R[i][0])
    ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"))))
    kind = [(float(e["t_s"]), e["detail"].split(" ")[0]) for e in ev if e["event"] == "shot_trigger"]
    app = []
    px = g[:-5] + "_ingameproxy" if g.endswith("_game") else None
    pf = os.path.join(px, "ingame_proxy_events.csv") if px else None
    if pf and os.path.exists(pf):
        app = [(int(r["t_ns"]) - t0ns) / 1e9 for r in csv.DictReader(open(pf)) if r["event"] == "tap_dispatch"]
    if not app: app = [float(e["t_s"]) for e in ev if e["event"] == "pressed"]
    hand = []
    pp = os.path.join(g, "presses.csv")
    if os.path.exists(pp):
        hand = [float(r["t_s"]) for r in csv.DictReader(open(pp)) if not any(-0.05 <= float(r["t_s"]) - x <= 0.12 for x in app)]
    clips = []
    cp = os.path.join(g, "clips.csv")
    if os.path.exists(cp):
        clips = [(float(c["event_t_s"]), c["tag"], c["folder"]) for c in csv.DictReader(open(cp)) if c["tag"].startswith("trig") or c["tag"].startswith("press")]
    cache = {}
    def verdict(t):
        c = [q for q in clips if -0.25 <= t - q[0] <= 0.25]
        if not c: return "noclip", ""
        q = min(c, key=lambda q: abs(t - q[0]))
        f = os.path.join(g, "clips", q[2])
        if not os.path.isdir(f): return "noclip", ""
        if f not in cache: cache[f] = cbl.scan_clip(f, TPLS, 0.66)
        peak, rel, v = cache[f]
        if rel is None: return "none", q[2]
        return ("BLOCK" if v.startswith("성공") else "fail"), q[2] + " " + re.sub(r"[^0-9.()]", "", v)
    bcache = {}
    def first_press(t):
        """Session time when the block button on screen FIRST changed in the clip of the press at t (any finger, logged or
        not), from the clip frames: mean luma of a 44 px box on the button (0.873, 0.842 of the frame). None = no clip."""
        c = [q for q in clips if -0.25 <= t - q[0] <= 0.25]
        if not c: return None
        q = min(c, key=lambda q: abs(t - q[0]))
        if q[2] in bcache: return bcache[q[2]]
        fr = sorted((float(re.search(r"_([+-][\d.]+)ms", p).group(1)), p) for p in glob.glob(os.path.join(g, "clips", q[2], "f*.jpg")))
        S = []
        for ms, p in fr:
            im = cv2.imread(p, cv2.IMREAD_GRAYSCALE); H, W = im.shape
            cx, cy = int(0.873 * W), int(0.842 * H); S.append((ms, float(im[cy - 22:cy + 22, cx - 22:cx + 22].mean())))
        r = None
        pre = [v for ms, v in S if ms < -350]
        if len(pre) >= 3 and max(pre) - min(pre) < 1.5:
            base = sorted(pre)[len(pre) // 2]
            for ms, v in S:
                if ms >= -350 and abs(v - base) > 3.0: r = q[0] + ms / 1000.0; break
        bcache[q[2]] = r; return r
    name = os.path.basename(g)[:15]
    vc = ""
    sp = os.path.join(g, "summary.txt")
    if os.path.exists(sp):
        m = re.search(r"vc (\d+)", open(sp, encoding="utf-8", errors="replace").read()); vc = m.group(1) if m else ""
    print("== %s vc%s  steps %d  app taps %d  hand presses %d" % (name, vc, len(steps), len(app), len(hand)))
    for s in steps:
        if not (T0 <= s <= T1): continue
        P = [(x, "app") for x in app if -0.3 <= s - x <= 1.2] + [(x, "hand") for x in hand if -0.3 <= s - x <= 1.2]
        if not P: print("  step %7.2f  no press" % s); continue
        for x, who in sorted(P):
            k = min(kind, key=lambda q: abs(q[0] - x)) if kind else None
            kk = k[1] if (who == "app" and k and abs(k[0] - x) < 0.3) else ""
            v, c = verdict(x)
            fp = first_press(x)
            # screen lead: from the first visible change of the button; "early" = the button changed >60 ms before our tap
            sl = "" if fp is None else "%.0f" % ((s - fp) * 1000)
            first = "" if fp is None else ("early" if x - fp > 0.06 else "same")
            print("  step %7.2f  %-4s %-6s lead %+5.0f ms  screen %5s %-5s  %-6s %s" % (s, who, kk, (s - x) * 1000, sl, first, v, c))
            allrows.append([name, vc, "%.2f" % s, who, kk, "%.0f" % ((s - x) * 1000), v, c, sl, first])
print("== leads (ms before release) per outcome; every press counts (not only the first of a step)")
for who in ("app", "hand"):
    for v in ("BLOCK", "fail", "none", "noclip"):
        L = sorted(int(r[5]) for r in allrows if r[3] == who and r[6] == v)
        if L: print("  %-4s %-6s n=%-3d %s" % (who, v, len(L), " ".join(str(x) for x in L)))
print("== SCREEN leads (first visible button change -> release), whoever pressed")
for v in ("BLOCK", "fail", "none"):
    for f in ("same", "early"):
        L = sorted(int(r[8]) for r in allrows if r[6] == v and r[9] == f)
        if L: print("  %-6s %-5s n=%-3d %s" % (v, f, len(L), " ".join(str(x) for x in L)))
if OUT:
    with open(OUT, "w", newline="") as f:
        w = csv.writer(f); w.writerow(["session", "vc", "step_s", "who", "kind", "lead_ms", "verdict", "clip", "screen_lead_ms", "first"]); w.writerows(allrows)
