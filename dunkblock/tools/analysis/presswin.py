"""Press lead time vs the game's verdict, PRACTICE sessions only (release clock = boxstep.py's slot-2 brightness step).

presswin.py <game folder> [<game folder> ...] [-from s] [-to s] [-csv out.csv]

For every release step: every block-button press in the 1.2 s before it and 0.3 s after it, as "lead" = ms from the
press to the release (+ = pressed before the ball left), who pressed (app = our injected tap, hand = the user's thumb,
demo = the game's own demonstration), and whether the game then drew the success word '블록'
(count_block_labels.scan_clip on the clip of that press: "BLOCK", "fail" = the word with more text after it
(위치 부족 / 타이밍 늦음 / 타이밍 빠름), "none" = no '블록' word at all (강한 컨테스트, 컨테스트 실패 or nothing),
"noclip" = that press has no clip in the bundle).
Press time: app = tap_dispatch in <session>_ingameproxy (the tap going down), else the `pressed` row of shot_events.csv;
hand = presses.csv (the button's brightness flash) that is not within 0.12 s of an app tap.

DEMO: every drill starts with two demonstrations in which the GAME blocks the shot by itself (skill description panel,
numbered buttons). The block button is then drawn lit from the game's own press until the release: shot.csv `dark`
(dark share of the block slot) falls from about 0.34 to under 0.10 for 0.17..0.30 s. An app tap inside a demonstration
did not cause the block ("demo" column = ms from the start of the lit stretch to the release, empty = no demonstration).
Rows `who = demo` are the demonstrations themselves (lead = the game's own press, on the screen clock).
Our own 33 ms tap leaves only a short mark on the button 28..59 ms after tap_dispatch (dark 0.34 -> 0.26).
The last block prints the leads per outcome, demonstrations apart. Do not use on match sessions (the step is not a
release clock there). HANDOFF 0-4z7.
"""
import sys, os, csv, re
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

def lit_stretches(rows):
    """(start_s, end_s) of every stretch where the block button is drawn lit for 0.15..0.6 s: `dark` under 60 % of its
    steady level in the half second before (start = first frame that left that level)."""
    T = [float(r["t_s"]) for r in rows]; D = [float(r["dark"]) for r in rows]; O = [float(r["orange"]) for r in rows]
    E = []; i = 30; n = len(T)
    while i < n:
        pre = sorted(D[i - 30:i - 3]); lo = pre[len(pre) // 2]
        if lo > 0.22 and pre[-1] - pre[0] < 0.08 and D[i] < 0.6 * lo and O[i] < 0.02:
            k = i
            while k > i - 4 and D[k - 1] < lo - 0.012: k -= 1
            j = i
            while j < n and D[j] < 0.75 * lo and T[j] - T[i] < 1.5: j += 1
            if j < n and 0.15 <= T[j] - T[k] <= 0.6: E.append((T[k], T[j]))
            i = j + 1
        else: i += 1
    return E

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
    lit = lit_stretches(rows)
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
    name = os.path.basename(g)[:15]
    vc = ""
    sp = os.path.join(g, "summary.txt")
    if os.path.exists(sp):
        m = re.search(r"vc (\d+)", open(sp, encoding="utf-8", errors="replace").read()); vc = m.group(1) if m else ""
    print("== %s vc%s  steps %d  app taps %d  hand presses %d  demonstrations %d" % (name, vc, len(steps), len(app), len(hand), len(lit)))
    for s in steps:
        if not (T0 <= s <= T1): continue
        dm = [e for e in lit if -0.1 <= s - e[0] <= 0.6 and abs(e[1] - s) <= 0.15]      # lit until the release = a demonstration
        demo = "%.0f" % ((s - dm[0][0]) * 1000) if dm else ""
        P = [(x, "app") for x in app if -0.3 <= s - x <= 1.2] + [(x, "hand") for x in hand if -0.3 <= s - x <= 1.2]
        if dm: P.append((dm[0][0], "demo"))
        if not P: print("  step %7.2f  no press" % s); continue
        for x, who in sorted(P):
            k = min(kind, key=lambda q: abs(q[0] - x)) if kind else None
            kk = k[1] if (who == "app" and k and abs(k[0] - x) < 0.3) else ""
            v, c = ("", "") if who == "demo" else verdict(x)
            print("  step %7.2f  %-4s %-6s lead %+5.0f ms  demo %4s  %-6s %s" % (s, who, kk, (s - x) * 1000, demo, v, c))
            allrows.append([name, vc, "%.2f" % s, who, kk, "%.0f" % ((s - x) * 1000), v, c, demo])
print("== leads (ms before release) per outcome; every press counts (not only the first of a step)")
for who in ("app", "hand"):
    for v in ("BLOCK", "fail", "none", "noclip"):
        L = sorted(int(r[5]) for r in allrows if r[3] == who and r[6] == v and not r[8])
        if L: print("  %-4s %-6s n=%-3d %s" % (who, v, len(L), " ".join(str(x) for x in L)))
L = sorted(int(r[5]) for r in allrows if r[3] == "demo")
if L: print("  the game's own press in its demonstrations n=%d  %s" % (len(L), " ".join(str(x) for x in L)))
L = sorted(int(r[5]) for r in allrows if r[3] == "app" and r[8])
if L: print("  app taps inside a demonstration (not counted above) n=%d  %s" % (len(L), " ".join(str(x) for x in L)))
if OUT:
    with open(OUT, "w", newline="") as f:
        w = csv.writer(f); w.writerow(["session", "vc", "step_s", "who", "kind", "lead_ms", "verdict", "clip", "demo_lead_ms"]); w.writerows(allrows)
