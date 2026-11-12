"""Press rule "dip, then the turn up" replayed on a disc track, with the near gate. (HANDOFF 0-4z13)

dipturn.py -sess <game folder> <sessrel output txt> [DEPTH 1.5] [RISE 0.7]
    track = d2 of shot.csv on defence (the app's own finder B, about 60 scans/s), releases of sessrel.py, my ring of
    matchup.csv.
dipturn.py -rec <track.csv of risewhole.py -track> <mfollow csv> <key.csv of mrel.py -key> [DEPTH] [RISE]
    the same on a hand-played recording.

Why: before a jump shot next to my player the disc sinks 2..5 radii (R) until about 0.35..0.30 s before the release
and then turns up; it passes its old height only 0.2..0.15 s before the release, so "rose 4 R" (rise2Press) comes too
late or never (relwhy.py, vc1100 and vc1092 matches). Rule at scan i:
  bottom = lowest disc of the last 0.20 s, at least 20 ms old; level = median height 0.40..0.15 s before the bottom;
  depth = bottom - level >= DEPTH R;  rise = bottom - now >= RISE R;  one disc all the way (no step > 2.5 R and no hole
  > 0.08 s between scans from the level window to now; disc size 0.7..1.4 of its 0.5 s median; ring >= 0.3).
Not the rule that failed in 0-4z7 ("rose 0.8..1.2 R above the low of the last 0.25 s"): that one asked for no dip.
Near gate = core NearGate (up <= 0.36, side <= 0.14 of my ring, median of <= 3 scans not older than 0.4 s). Locks: 1.5 s
after a press, 0.4 s after a trigger that was not near. Graded on the EVENT clock: win -305..-220 ms, contest -220..-150,
early -600..-305, vearly < -600, late -150..+100. Timing only, not a game verdict; a release is ANY opponent's shot.
"""
import sys, os, csv, re, bisect
import numpy as np

a = sys.argv[1:]; mode = a[0]
DEPTH = float(a[4 if mode == "-rec" else 3]) if len(a) > (4 if mode == "-rec" else 3) else 1.5
RISE = float(a[5 if mode == "-rec" else 4]) if len(a) > (5 if mode == "-rec" else 4) else 0.7
REL = []; UNK = []
if mode == "-sess":
    g = a[1]; name = os.path.basename(g)
    for l in open(a[2], encoding="utf-8"):
        m = re.match(r"\s+(\d+\.\d+)\s+(firm|noisy|dim|shift)", l)
        if m: REL.append((float(m.group(1)), m.group(2) == "firm", ""))
    M = [(float(r["t_s"]), float(r["me_x"]), float(r["me_y"])) for r in csv.DictReader(open(os.path.join(g, "matchup.csv"), encoding="utf-8")) if float(r["me_x"]) >= 0]
    F = []; ndef = 0
    with open(os.path.join(g, "shot.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["side"] != "def": continue
            ndef += 1
            if r.get("d2") == "1": F.append((float(r["t_s"]), float(r["x2"]) * 1560.0, float(r["y2"]) * 720.0, (int(r["n2"]) / np.pi) ** 0.5, float(r["ring2"])))
    hdef = ndef * 0.017
else:
    name = os.path.basename(a[3]).split(".")[0]; M = []; hdef = 0.0; pt = None
    for r in csv.DictReader(open(a[2], encoding="utf-8")):
        ts = float(r["t_s"])
        if pt is not None and 0.20 <= pl <= 0.27: hdef += min(ts - pt, 0.5)
        pt = ts; pl = float(r["btn_label"])
        if float(r["me_x"]) >= 0: M.append((ts, float(r["me_x"]), float(r["me_y"])))
    F = [(float(r["t_s"]), float(r["x"]), float(r["y"]), (int(r["n"]) / np.pi) ** 0.5, float(r["ring"])) for r in csv.DictReader(open(a[1]))]
    for r in csv.DictReader(open(a[3], encoding="utf-8")):
        if r["release_rel"]: REL.append((float(r["release_s"]), not r["src"].endswith("?"), r["name"] + r["k"]))
        else: UNK.append(float(r["ref_s"]))
t, x, y, rr, ring = np.array(F, np.float64).T; MT = [m[0] for m in M]


def near(ts, px, py):
    i = bisect.bisect_right(MT, ts) - 1; c = []
    while i >= 0 and ts - MT[i] <= 0.4 and len(c) < 3: c.append(M[i]); i -= 1
    if not c: return -1
    mx = sorted(q[1] for q in c)[len(c) // 2]; my = sorted(q[2] for q in c)[len(c) // 2]
    return 1 if my / 540.0 - py / 720.0 <= 0.36 and abs(mx / 1170.0 - px / 1560.0) <= 0.14 else 0


ALL = []; NEAR = []; la = -9.0; ln = -9.0
for i in range(len(t)):
    if ring[i] < 0.3: continue
    w = int(np.searchsorted(t, t[i] - 0.20))
    if i - w < 5: continue
    b = w + int(np.argmax(y[w:i + 1]))
    if t[i] - t[b] < 0.02: continue
    l0 = int(np.searchsorted(t, t[b] - 0.40)); l1 = int(np.searchsorted(t, t[b] - 0.15))
    if l1 - l0 < 4: continue
    q = int(np.searchsorted(t, t[i] - 0.5)); R = float(np.median(rr[q:i + 1]))
    if not 0.7 * R <= rr[b] <= 1.4 * R or not 0.7 * R <= rr[i] <= 1.4 * R: continue
    if (y[b] - y[i]) / R < RISE or (y[b] - float(np.median(y[l0:l1]))) / R < DEPTH: continue
    dt = np.diff(t[l0:i + 1]); dx = np.abs(np.diff(x[l0:i + 1])); dy = np.abs(np.diff(y[l0:i + 1]))
    if dt.max() > 0.08 or dx.max() > 2.5 * R or dy.max() > 2.5 * R: continue
    n = near(t[i], x[i], y[i])
    if t[i] - la >= 1.5: la = float(t[i]); ALL.append((la, n))
    if t[i] - ln >= 1.5:
        if n == 1: ln = float(t[i]); NEAR.append((ln, n))
        else: ln = float(t[i]) - 1.1
print("%s: defence %.0f s, %d disc scans, %d releases (%d firm) + %d without a release time | dip >= %.1f R then rise >= %.1f R: %d triggers (near 1: %d, 0: %d, no ring: %d)" % (
    name, hdef, len(t), len(REL), sum(1 for q in REL if q[1]), len(UNK), DEPTH, RISE, len(ALL), sum(1 for q in ALL if q[1] == 1), sum(1 for q in ALL if q[1] == 0), sum(1 for q in ALL if q[1] < 0)))
for lab, P in (("all", [q[0] for q in ALL]), ("near only", [q[0] for q in NEAR])):
    cnt = {}; used = set(); rows = []
    for Rt, firm, nm in REL:
        c = [p for p in P if Rt - 1.0 <= p <= Rt + 0.1]
        if not c: k = "none"; e = None
        else:
            e = min(c, key=lambda p: abs(p - (Rt - 0.26))); used.add(e); d = e - Rt
            k = "win" if -0.305 <= d <= -0.22 else "contest" if -0.22 < d <= -0.15 else "early" if -0.6 <= d < -0.305 else "vearly" if d < -0.6 else "late"
        cnt[k] = cnt.get(k, 0) + 1
        if e is not None: rows.append("%s%s %s %+d" % (nm or "%.2f" % Rt, "" if firm else "~", k, round((e - Rt) * 1000)))
    rest = [p for p in P if p not in used]; unk = [p for p in rest if any(u - 3.0 <= p <= u + 0.3 for u in UNK)]; stray = [p for p in rest if p not in unk]
    print("  %-9s presses %2d | %s | unk %d | no shot %d (%.1f per defence minute)" % (lab, len(P), " ".join("%s %d" % (k, cnt.get(k, 0)) for k in ("win", "contest", "early", "vearly", "late", "none")),
          len(unk), len(stray), 60.0 * len(stray) / max(hdef, 1.0)))
    print("     " + ", ".join(rows))
    print("     no shot: " + " ".join("%.1f" % p for p in stray))
