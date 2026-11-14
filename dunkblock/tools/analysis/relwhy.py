"""Per release of a match session: what did the disc track offer before it? (HANDOFF 0-4z13)

relwhy.py <game folder> <sessrel output txt> [W 0.12]

For every release of sessrel.py (any opponent's shot): d2 scans seen in -0.6..-0.1 s, the near gate (core NearGate
replayed from matchup.csv) at the last disc seen before -0.15 s, the largest rise over W seconds (in disc radii R, same
size and sideways tests as ShotWatcher.rise2) inside -0.6..-0.15 s, and the time before the release at which the rise
first reached 2, 3 and 4 R. Class: nodisc (fewer than 5 scans), far (near gate 0), noring (my ring unknown), flat (near,
rise never 4 R inside the window), rise (near and 4 R reached; the time tells whether it was in the block window
-305..-220 ms). Shows where the releases go for ANY disc-rise rule: a release that is nodisc or far cannot be pressed by
it at all, a flat one needs another cue.
"""
import sys, os, csv, re
import numpy as np
a = sys.argv[1:]; g = a[0]; W = float(a[2]) if len(a) > 2 else 0.12
rel = []
for l in open(a[1], encoding="utf-8"):
    m = re.match(r"\s+(\d+\.\d+)\s+(firm|noisy|dim|shift)", l)
    if m: rel.append((float(m.group(1)), m.group(2)))
M = [(float(r["t_s"]), float(r["me_x"]), float(r["me_y"])) for r in csv.DictReader(open(os.path.join(g, "matchup.csv"), encoding="utf-8")) if float(r["me_x"]) >= 0]
F = []
with open(os.path.join(g, "shot.csv"), encoding="utf-8") as f:
    for r in csv.DictReader(f):
        if r["side"] == "def" and r.get("d2") == "1": F.append((float(r["t_s"]), float(r["x2"]) * 1560.0, float(r["y2"]) * 720.0, (int(r["n2"]) / np.pi) ** 0.5, float(r["ring2"])))
t, x, y, rr, ring = np.array(F, np.float64).T


def med(v): v = sorted(v); return v[len(v) // 2]


def near(ts, px, py):
    c = [m for m in M if m[0] <= ts and ts - m[0] <= 0.4][-3:]
    if not c: return -1
    return 1 if med([m[2] for m in c]) / 540 - py <= 0.36 and abs(med([m[1] for m in c]) / 1170 - px) <= 0.14 else 0


def rise_at(i):
    if ring[i] < 0.3: return None
    j = int(np.searchsorted(t, t[i] - W))
    if j >= i or abs(t[j] - (t[i] - W)) > 0.02 or t[i] - t[j - 1 if j else 0] > W + 0.06: return None
    q = int(np.searchsorted(t, t[i] - 0.5)); R = float(np.median(rr[q:i + 1]))
    if not 0.7 * R <= rr[j] <= 1.4 * R or not 0.7 * R <= rr[i] <= 1.4 * R: return None
    rise = (y[j] - y[i]) / R
    return rise if abs(x[i] - x[j]) / R <= 1.5 * max(rise, 0.0) else None


cls = {}; print("%s: %d releases, W %.2f" % (os.path.basename(g), len(rel), W))
for R, src in rel:
    i0 = int(np.searchsorted(t, R - 0.6)); i1 = int(np.searchsorted(t, R - 0.1)); i2 = int(np.searchsorted(t, R - 0.15))
    n = i1 - i0; best = -9.0; first = {2: None, 3: None, 4: None}
    for i in range(i0, i2):
        r = rise_at(i)
        if r is None: continue
        best = max(best, r)
        for d in first:
            if first[d] is None and r >= d: first[d] = round((t[i] - R) * 1000)
    ng = near(t[i2 - 1], x[i2 - 1] / 1560.0, y[i2 - 1] / 720.0) if i2 > i0 else -1
    k = "nodisc" if n < 5 else "far" if ng == 0 else "noring" if ng < 0 else "rise" if first[4] is not None else "flat"
    cls[k] = cls.get(k, 0) + 1
    print("  %7.2f %-5s scans %2d near %2d  max rise %5.1f R  first>=2R %5s  >=3R %5s  >=4R %5s  -> %s" % (R, src, n, ng, best if best > -9 else float("nan"),
          first[2], first[3], first[4], k))
print("  classes: " + " ".join("%s %d" % kv for kv in sorted(cls.items())))
